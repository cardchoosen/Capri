package ai.capri.app.extensions.hub

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64

class PublicSkillTest {
    @get:Rule val temp=TemporaryFolder()
    private val sha="a".repeat(40)
    private fun source(id:String="example-skill")=File(temp.root,id).apply { mkdirs();File(this,"SKILL.md").writeText("---\nname: $id\ndescription: Inspect example text without changing files.\n---\n\nRead input and report actual evidence.\n") }
    private fun packed()=HubPackage.capture(source(),"example-skill")
    private open class Remote:HubRemote {
        var writes=0;var branches=0;var prs=0;var prExists=false;var branchExists=false;var losePr=false
        val requests=mutableListOf<String>();val sha="a".repeat(40)
        override suspend fun raw(commit:String,path:String):ByteArray=error("Unexpected raw download")
        override suspend fun api(method:String,path:String,body:JSONObject?,token:String?):JSONObject {
            requests+="$method $path";if(method!="GET")writes++
            return when {
                path=="/repos/cardchoosen/capri-skills" -> JSONObject().put("id",1).put("private",false).put("default_branch","main")
                path=="/repos/contributor/capri-skills" -> JSONObject().put("parent",JSONObject().put("id",1)).put("full_name","contributor/capri-skills")
                path.endsWith("/git/ref/heads/main") -> JSONObject().put("object",JSONObject().put("sha",sha))
                path.contains("/git/commits/") -> JSONObject().put("tree",JSONObject().put("sha",sha))
                path.contains("/git/trees/") -> JSONObject().put("truncated",false).put("tree",JSONArray())
                path.endsWith("/git/blobs") -> JSONObject().put("sha",sha)
                path.endsWith("/git/trees") -> JSONObject().put("sha",sha)
                path.endsWith("/git/commits") -> JSONObject().put("sha",sha)
                path.contains("/git/ref/heads/capri/") -> if(branchExists)JSONObject().put("object",JSONObject().put("sha",sha))else throw HubHttpError(404)
                path.endsWith("/git/refs") -> {branchExists=true;branches++;JSONObject().put("ref",body!!.getString("ref"))}
                path.contains("/pulls?") -> JSONObject().put("items",JSONArray().apply{if(prExists)put(JSONObject().put("number",7).put("html_url","https://github.com/cardchoosen/capri-skills/pull/7"))})
                path.endsWith("/pulls") -> {prExists=true;prs++;if(losePr)throw IOException("Response lost after creating PR");JSONObject().put("number",7).put("html_url","https://github.com/cardchoosen/capri-skills/pull/7")}
                else -> error("Unexpected API path: $path")
            }
        }
    }
    @Test fun preparationCannotPublishAndDeclinesAreRememberedAcrossChats()=runBlocking<Unit> {
        val remote=Remote();val hub=HubContribution(File(temp.root,"contributions"),remote);val src=source();val prepared=hub.prepare("chat",src,"example-skill");val key=prepared.getString("key")
        assertEquals("awaiting_user",prepared.getString("state"));assertEquals(0,remote.writes)
        assertThrows(IllegalArgumentException::class.java){runBlocking{hub.publish("chat",key,"contributor","secret"){true}}}
        assertThrows(IllegalArgumentException::class.java){HubContract.validate(JSONObject().put("action","submit").put("confirmed",true))}
        hub.decline("chat",key);assertEquals("declined",hub.prepare("other",src,"example-skill").getString("state"));assertEquals(0,remote.writes)
    }
    @Test fun approvedPublicationIsIdempotentEvenWhenThePrResponseIsLost()=runBlocking<Unit> {
        val remote=Remote().apply{losePr=true};val root=File(temp.root,"contributions");val hub=HubContribution(root,remote);val prepared=hub.prepare("chat",source(),"example-skill");val key=prepared.getString("key");hub.approve("chat",key)
        assertThrows(IOException::class.java){runBlocking{hub.publish("chat",key,"contributor","secret"){true}}}
        assertEquals("failed",hub.read("chat",key).getString("state"));remote.losePr=false
        val result=HubContribution(root,remote).publish("chat",key,"contributor","secret"){true}
        assertEquals("submitted",result.getString("state"));assertEquals(1,remote.prs);assertEquals(1,remote.branches)
        hub.publish("chat",key,"contributor","secret"){true};assertEquals(1,remote.prs)
        assertTrue(remote.requests.none{it.startsWith("PATCH")});assertFalse(result.toString().contains("secret"))
    }
    @Test fun explicitRetryOfInterruptedPublicationReconcilesAnExistingPr()=runBlocking<Unit> {
        val remote=Remote();val root=File(temp.root,"contributions");val hub=HubContribution(root,remote)
        val key=hub.prepare("chat",source(),"example-skill").getString("key");hub.approve("chat",key)
        val submitted=hub.publish("chat",key,"contributor","secret"){true}
        val receipt=File(root,"chat/$key/record.json")
        receipt.writeText(JSONObject(submitted.toString()).put("state","publishing").toString())
        val restored=HubContribution(root,remote);restored.approve("chat",key)
        assertEquals("submitted",restored.publish("chat",key,"contributor","secret"){true}.getString("state"))
        assertEquals(1,remote.prs);assertEquals(1,remote.branches)
    }
    @Test fun changedPreviewAndChangedAccountCannotBeSilentlyPublished()=runBlocking<Unit> {
        val remote=Remote();val root=File(temp.root,"contributions");val hub=HubContribution(root,remote);val key=hub.prepare("chat",source(),"example-skill").getString("key");hub.approve("chat",key)
        File(root,"chat/$key/files/SKILL.md").appendText("changed")
        assertThrows(IllegalArgumentException::class.java){runBlocking{hub.publish("chat",key,"contributor","secret"){true}}};assertEquals(0,remote.writes)
    }
    @Test fun authorizationRevocationStopsBeforeAnyPublicRequest()=runBlocking<Unit> {
        val remote=Remote();val hub=HubContribution(File(temp.root,"contributions"),remote);val key=hub.prepare("chat",source(),"example-skill").getString("key");hub.approve("chat",key)
        assertThrows(IllegalStateException::class.java){runBlocking{hub.publish("chat",key,"contributor","secret"){false}}}
        assertEquals(0,remote.writes);assertTrue(remote.requests.isEmpty())
    }
    @Test fun privatePathsSecretsAndSymlinksAreRejectedBeforeApproval() {
        val src=source();File(src,"notes.txt").writeText("api_key=abcdefghijklmnopqrstuv")
        assertThrows(IllegalArgumentException::class.java){HubPackage.capture(src,"example-skill")}
        File(src,"notes.txt").delete();File(src,".env").writeText("private")
        assertThrows(IllegalArgumentException::class.java){HubPackage.capture(src,"example-skill")}
        File(src,".env").delete();java.nio.file.Files.createSymbolicLink(File(src,"link.txt").toPath(),File(src,"SKILL.md").toPath())
        assertThrows(IllegalArgumentException::class.java){HubPackage.capture(src,"example-skill")}
        assertThrows(IllegalArgumentException::class.java){HubPackage.path("../outside")}
    }
    @Test fun completeCatalogPublicationAndIntegrityFailuresRetainTheOldCache()=runBlocking<Unit> {
        val pack=packed();var corrupt=false;var current=sha;val commits=mutableSetOf<String>()
        val remote=object:Remote(){
            override suspend fun api(method:String,path:String,body:JSONObject?,token:String?):JSONObject=if(path.endsWith("/main"))JSONObject().put("object",JSONObject().put("sha",current))else super.api(method,path,body,token)
            override suspend fun raw(commit:String,path:String):ByteArray {
                commits+=commit
                return if(path=="index.json")JSONObject().put("schema",1).put("skills",JSONArray().put(pack.manifest())).toString().toByteArray()
                else if(corrupt)"wrong".toByteArray() else pack.files.getValue(path.removePrefix("skills/${pack.id}/"))
            }
        }
        val catalog=HubCatalog(File(temp.root,"catalog"),remote);assertEquals("ok",catalog.sync().getString("status"));assertEquals(sha,catalog.status().getString("commit"));assertTrue(File(catalog.sources().single(),"SKILL.md").isFile)
        File(catalog.sources().single(),"SKILL.md").delete();assertEquals("ok",catalog.sync().getString("status"));assertTrue(File(catalog.sources().single(),"SKILL.md").isFile)
        current="b".repeat(40);corrupt=true
        assertThrows(IllegalArgumentException::class.java){runBlocking{catalog.sync()}}
        assertEquals(sha,catalog.status().getString("commit"));assertTrue(catalog.sources().single().isDirectory);assertEquals(setOf(sha,current),commits)
    }
    @Test fun installedPublicUpdatesPreservePrivateEditsNamesAndUserDeletion() {
        val src=source();val installed=File(temp.root,"managed").apply{mkdirs()};val target=File(installed,"example-skill");val disabled=mutableSetOf<String>()
        val installer=HubInstaller(File(temp.root,"installer/state.json"))
        fun apply()=installer.apply(listOf(src),installed,emptySet(),{input,replace->if(replace)target.deleteRecursively();input.copyRecursively(target)},disabled::add)
        assertEquals(1,apply().getInt("changed"));File(src,"SKILL.md").appendText("New public version.\n");assertEquals(1,apply().getInt("changed"))
        File(target,"SKILL.md").appendText("Private user edit.\n");File(src,"SKILL.md").appendText("Another public version.\n")
        assertEquals(0,apply().getInt("changed"));assertTrue(File(target,"SKILL.md").readText().contains("Private user edit."))
        target.deleteRecursively();assertEquals(0,apply().getInt("changed"));assertFalse(target.exists())
        val privateTarget=File(installed,"example-skill").apply{mkdirs()};File(privateTarget,"SKILL.md").writeText(File(src,"SKILL.md").readText()+"Personal version")
        val separate=HubInstaller(File(temp.root,"separate/state.json"));assertEquals(0,separate.apply(listOf(src),installed,emptySet(),{_,_->fail("Must not replace same-name personal Skill")},{}).getInt("changed"))
    }
    @Test fun withdrawnUnmodifiedPublicSkillIsDisabledAndBuiltinsAreNeverInstalled() {
        val src=source();val installed=File(temp.root,"installed").apply{mkdirs()};val disabled=mutableSetOf<String>();val installer=HubInstaller(File(temp.root,"state/state.json"))
        installer.apply(listOf(src),installed,emptySet(),{input,_->input.copyRecursively(File(installed,input.name));Unit},disabled::add)
        installer.apply(emptyList(),installed,emptySet(),{_,_->fail()},disabled::add);assertEquals(setOf("example-skill"),disabled)
        HubInstaller(File(temp.root,"builtin/state.json")).apply(listOf(src),installed,setOf("example-skill"),{_,_->fail("APK-owned Skill must not be replaced")},{})
    }
    @Test fun publicHttpDownloadsAreAnonymousAndRedirectsNeverForwardTokens()=runBlocking<Unit> {
        val requests=mutableListOf<okhttp3.Request>()
        val client=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests+=chain.request()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(okhttp3.ResponseBody.create(null,"{}" )).build()
        }.build()
        val remote=HubGitHub(client);remote.api("GET","/user",token="fixture-secret");remote.raw(sha,"index.json")
        assertEquals("api.github.com",requests[0].url.host);assertEquals("Bearer fixture-secret",requests[0].header("Authorization"))
        assertEquals("raw.githubusercontent.com",requests[1].url.host);assertNull(requests[1].header("Authorization"))
        assertThrows(IllegalArgumentException::class.java){runBlocking{remote.api("GET","//evil.test",token="fixture-secret")}}
        var redirectedRequests=0
        val redirect=HubGitHub(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            redirectedRequests++;okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(302).message("Redirect").header("Location","https://elsewhere.test/").body(okhttp3.ResponseBody.create(null,"redirect")).build()
        }.build())
        assertThrows(HubHttpError::class.java){runBlocking{redirect.api("GET","/user",token="fixture-secret")}};assertEquals(1,redirectedRequests)
    }
    @Test fun androidPackageDigestMatchesTheRepositoryValidator() {
        val fixture=JSONObject(javaClass.classLoader!!.getResourceAsStream("public-skill-package.json")!!.bufferedReader().use{it.readText()})
        val id=fixture.getString("id");val root=File(temp.root,id).apply{mkdirs()};val files=fixture.getJSONObject("files")
        for(name in files.keys()){val file=File(root,name);file.parentFile!!.mkdirs();file.writeText(files.getString(name))}
        assertEquals(fixture.getString("revision"),HubPackage.capture(root,id).digest)
    }
    @Test fun emptyPublicCatalogIsAValidInitialRepository()=runBlocking<Unit> {
        val remote=object:Remote(){override suspend fun raw(commit:String,path:String)="{\"schema\":1,\"skills\":[]}".toByteArray()}
        val catalog=HubCatalog(File(temp.root,"empty"),remote);assertEquals(0,catalog.sync().getInt("skill_count"));assertTrue(catalog.sources().isEmpty())
    }
}
