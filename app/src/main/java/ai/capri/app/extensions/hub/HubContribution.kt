package ai.capri.app.extensions.hub

import ai.capri.app.agent.reliability.DurableJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Approval is a native UI action over a frozen package, never a model-supplied boolean. */
internal class HubContribution(private val root:File,private val remote:HubRemote) {
    private val choices=DurableJson(File(root.parentFile,"public-skill-choices.json"))
    private fun remember(key:String,record:JSONObject) { choices.access { data -> require(data.has(key) || data.length()<512) { "Public contribution choice capacity reached." };if(data.optJSONObject(key)?.optString("state")!="submitted")data.put(key,JSONObject().put("state",record.getString("state")).put("pull_request",record.optString("pull_request")).put("number",record.optInt("number"))) } }
    private fun dir(chat:String,key:String):File { require(chat.matches(Regex("[A-Za-z0-9-]+")) && key.matches(Regex("[a-f0-9]{64}")));return File(root,"$chat/$key") }
    fun prepare(chat:String,source:File,id:String):JSONObject {
        val pack=HubPackage.capture(source,id);val key=HubPackage.hash("${pack.id}:${pack.digest}".toByteArray());val location=dir(chat,key)
        require(location.exists() || File(root,chat).listFiles().orEmpty().count{it.isDirectory}<32) { "Contribution receipt capacity reached for this chat." }
        return DurableJson(File(location,"record.json")).access { record ->
            if(record.length()==0) {
                val prior=choices.access { data -> require(data.has(key) || data.length()<512) { "Public contribution choice capacity reached." };val previous=data.optJSONObject(key)?.let{JSONObject(it.toString())};if(previous==null)data.put(key,JSONObject().put("state","pending"));previous?.takeIf{it.optString("state") in setOf("declined","submitted")} }
                if(prior==null)for((path,bytes) in pack.files) { val out=File(location,"files/$path");out.parentFile!!.mkdirs();out.writeBytes(bytes) }
                record.put("key",key).put("chat",chat).put("id",id).put("digest",pack.digest).put("state","awaiting_user").put("manifest",pack.manifest()).put("created_at",System.currentTimeMillis())
                if(prior!=null)record.put("state",prior.getString("state")).put("pull_request",prior.optString("pull_request")).put("acknowledged",true)
            }
            JSONObject(record.toString())
        }
    }
    fun read(chat:String,key:String):JSONObject=DurableJson(File(dir(chat,key),"record.json")).access { JSONObject(it.toString()) }
    fun list(chat:String):JSONArray {
        require(chat.matches(Regex("[A-Za-z0-9-]+")))
        return JSONArray(File(root,chat).listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) }.sortedByDescending { it.lastModified() }.map { read(chat,it.name) }.take(32))
    }
    fun decision(chat:String,key:String,approve:Boolean):JSONObject=DurableJson(File(dir(chat,key),"record.json")).access { record ->
        require(record.optString("state") in setOf("awaiting_user","approved","failed")) { "Contribution has already been settled." }
        record.put("state",if(approve)"approved" else "declined");JSONObject(record.toString())
    }
    fun decline(chat:String,key:String):JSONObject=decision(chat,key,false).also{remember(key,it)}
    fun resource(chat:String,key:String,path:String):String {
        val record=read(chat,key);val pack=packageFor(chat,key,record);return pack.files.getValue(HubPackage.path(path)).toString(Charsets.UTF_8)
    }
    private fun packageFor(chat:String,key:String,record:JSONObject):HubPackage {
        val manifest=record.getJSONObject("manifest");val files=manifest.getJSONArray("files");val values=linkedMapOf<String,ByteArray>()
        for(i in 0 until files.length()) { val entry=files.getJSONObject(i);val path=HubPackage.path(entry.getString("path"));val file=File(dir(chat,key),"files/$path")
            require(file.isFile && file.length()<=1_000_000 && !java.nio.file.Files.isSymbolicLink(file.toPath()));val bytes=file.readBytes();require(HubPackage.hash(bytes)==entry.getString("sha256"));values[path]=bytes }
        return HubPackage(record.getString("id"),values).also { require(it.digest==record.getString("digest")) { "Prepared public files changed; request a new preview and approval." } }
    }
    private fun update(chat:String,key:String,block:(JSONObject)->Unit)=DurableJson(File(dir(chat,key),"record.json")).access { block(it);JSONObject(it.toString()) }
    suspend fun publish(chat:String,key:String,username:String,token:String,stillAuthorized:()->Boolean):JSONObject {
        var record=read(chat,key);if(record.optString("state")=="submitted")return record
        require(record.optString("state") in setOf("approved","failed","publishing") && record.optBoolean("approved_by_user")) { "Public contribution requires native user approval of this exact package." }
        val pack=packageFor(chat,key,record)
        require(username.matches(Regex("[A-Za-z0-9-]{1,39}")))
        val account=HubPackage.hash(username.lowercase().toByteArray());require(record.optString("account",account)==account) { "Contribution account changed; use the original account to reconcile the pending public request." }
        suspend fun api(method:String,path:String,body:JSONObject?=null):JSONObject { check(stillAuthorized()) { "GitHub authorization changed; inspect the saved contribution before retrying." };return remote.api(method,path,body,token) }
        record=update(chat,key) { it.put("account",account).put("state","publishing") }
        try {
            val upstream=api("GET","/repos/${HubGitHub.REPOSITORY}");require(!upstream.getBoolean("private"))
            val repo=if(username.equals("cardchoosen",true))HubGitHub.REPOSITORY else {
                val fork=try { api("GET","/repos/$username/capri-skills") } catch(e:HubHttpError) { if(e.code!=404)throw e;api("POST","/repos/${HubGitHub.REPOSITORY}/forks",JSONObject().put("default_branch_only",true)) }
                require(fork.optJSONObject("parent")?.optLong("id")==upstream.getLong("id")) { "The target repository is not a ready fork of Capri Skills; retry after GitHub finishes creating it." }
                val full=fork.getString("full_name");require(full.equals("$username/capri-skills",true));full
            }
            val branch="capri/skill-${pack.id}-${pack.digest.take(16)}"
            // Only an approved package reaches this point: creating a fork/branch publishes data publicly.
            if(!record.has("commit_sha")) {
                val prior=try{api("GET","/repos/$repo/git/ref/heads/$branch")}catch(error:HubHttpError){if(error.code!=404)throw error;null}
                if(prior!=null) {
                    val commit=api("GET","/repos/$repo/git/commits/${prior.getJSONObject("object").getString("sha")}")
                    val tree=api("GET","/repos/$repo/git/trees/${commit.getJSONObject("tree").getString("sha")}?recursive=1")
                    require(!tree.optBoolean("truncated"));val entries=tree.getJSONArray("tree");val prefix="skills/${pack.id}/"
                    val actual=(0 until entries.length()).map{entries.getJSONObject(it)}.filter{it.optString("type")=="blob" && it.getString("path").startsWith(prefix)}.associate{it.getString("path").removePrefix(prefix) to it.getString("sha")}
                    val expected=pack.files.mapValues{(_,bytes)->java.security.MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray()+bytes).joinToString(""){"%02x".format(it)}}
                    require(actual==expected) { "Existing public branch contains different files; review it before retrying." }
                    record=update(chat,key){it.put("commit_sha",prior.getJSONObject("object").getString("sha")).put("fork",repo).put("branch",branch).put("base_branch",upstream.getString("default_branch"))}
                }
            }
            if(!record.has("base_sha") && !record.has("commit_sha")) {
                val base=api("GET","/repos/${HubGitHub.REPOSITORY}/git/ref/heads/${upstream.getString("default_branch")}").getJSONObject("object").getString("sha")
                record=update(chat,key) { it.put("base_sha",base).put("base_branch",upstream.getString("default_branch")) }
            }
            if(!record.has("commit_sha")) {
                val base=api("GET","/repos/$repo/git/commits/${record.getString("base_sha")}")
                val entries=JSONArray()
                // Existing removed resources are explicitly deleted, while unrelated Skills remain intact.
                val previous=api("GET","/repos/$repo/git/trees/${base.getJSONObject("tree").getString("sha")}?recursive=1")
                require(!previous.optBoolean("truncated")) { "Repository tree is truncated; cannot safely construct the contribution." }
                val tree=previous.getJSONArray("tree");val prefix="skills/${pack.id}/"
                for(i in 0 until tree.length()) { val item=tree.getJSONObject(i);val path=item.getString("path");if(item.optString("type")=="blob" && path.startsWith(prefix) && path.removePrefix(prefix) !in pack.files)entries.put(JSONObject().put("path",path).put("mode","100644").put("type","blob").put("sha",JSONObject.NULL)) }
                for((path,bytes) in pack.files) {
                    val blob=api("POST","/repos/$repo/git/blobs",JSONObject().put("content",Base64.getEncoder().encodeToString(bytes)).put("encoding","base64"))
                    entries.put(JSONObject().put("path","skills/${pack.id}/$path").put("mode","100644").put("type","blob").put("sha",blob.getString("sha")))
                }
                val newTree=api("POST","/repos/$repo/git/trees",JSONObject().put("base_tree",base.getJSONObject("tree").getString("sha")).put("tree",entries))
                val commit=api("POST","/repos/$repo/git/commits",JSONObject().put("message","Contribute ${pack.id}\n\nCapri-Package: ${pack.digest}").put("tree",newTree.getString("sha")).put("parents",JSONArray(listOf(record.getString("base_sha")))))
                record=update(chat,key) { it.put("commit_sha",commit.getString("sha")).put("fork",repo).put("branch",branch) }
            }
            val existing=try { api("GET","/repos/$repo/git/ref/heads/$branch") }catch(e:HubHttpError){if(e.code!=404)throw e;null}
            if(existing==null)api("POST","/repos/$repo/git/refs",JSONObject().put("ref","refs/heads/$branch").put("sha",record.getString("commit_sha")))
            else require(existing.getJSONObject("object").getString("sha")==record.getString("commit_sha")) { "Contribution branch changed; inspect it instead of overwriting public work." }
            val head="$username:$branch";val query=java.net.URLEncoder.encode(head,"UTF-8")
            val pulls=api("GET","/repos/${HubGitHub.REPOSITORY}/pulls?state=all&head=$query").getJSONArray("items")
            val pr=if(pulls.length()>0)pulls.getJSONObject(0)else api("POST","/repos/${HubGitHub.REPOSITORY}/pulls",JSONObject().put("title","Skill: ${pack.id}").put("head",head).put("base",record.getString("base_branch"))
                .put("body","User-approved public Skill contribution.\n\nSkill: `${pack.id}`\nPackage SHA-256: `${pack.digest}`\nFiles: ${pack.files.size}\n\nNo scripts were executed during submission. The contributor approved publication under this repository's MIT license. Review scope, dependencies and behavior before merging."))
            val url=pr.getString("html_url");require(url.matches(Regex("https://github\\.com/${HubGitHub.REPOSITORY}/pull/[0-9]+")))
            return update(chat,key) { it.put("state","submitted").put("pull_request",url).put("number",pr.getInt("number")) }.also{remember(key,it)}
        } catch(error:Exception) { update(chat,key) { it.put("state","failed").put("error_code",if(error is HubHttpError)"github_http_${error.code}" else "publish_unconfirmed") };throw error }
    }
    fun acknowledge(chat:String,key:String) = update(chat,key) { it.put("acknowledged",true) }
    fun approve(chat:String,key:String):JSONObject { decision(chat,key,true);return update(chat,key) { it.put("approved_by_user",true) } }
}
