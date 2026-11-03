package ai.capri.app.extensions

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicitly configured HTTPS catalogs, bounded downloads, complete publication or old-cache reuse. */
internal class SkillRemote(private val root:File, private val download:((String)->ByteArray)?=null) {
    private val http=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
    fun directory(id:String)=File(root,ExtensionPolicy.id(id))
    fun failure(id:String)=File(root,"${ExtensionPolicy.id(id)}.error").takeIf{it.exists()}?.readText()
    @Synchronized fun refresh(id:String,url:String,cancel:()->Unit={}):JSONObject {
        val target=directory(id);val stage=File(root,".stage-${UUID.randomUUID()}");val backup=File(root,".old-${UUID.randomUUID()}")
        try {
            val base=validateUrl(url).trimEnd('/')+"/";val origin=base.toHttpUrl()
            fun fetch(address:String):ByteArray {
                cancel()
                if(download!=null)return download.invoke(address).also{require(it.size<=2_000_000){"Skill file exceeds 2 MB."}}
                var current=address.toHttpUrl()
                repeat(4) {
                    cancel();require(current.scheme==origin.scheme&&current.host==origin.host&&current.port==origin.port){"Cross-origin Skill download rejected."}
                    http.newCall(Request.Builder().url(current).build()).execute().use { response ->
                        if(response.isRedirect) { current=current.resolve(response.header("Location")?:error("Invalid Skill redirect."))?:error("Invalid Skill redirect.");return@use }
                        require(response.isSuccessful){"Skill download failed with HTTP ${response.code}."}
                        val body=response.body?:error("Empty Skill response.");require(body.contentLength()<=2_000_000){"Skill file exceeds 2 MB."}
                        return body.byteStream().use { input ->
                            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                            while(out.size()<=2_000_000) { cancel();val n=input.read(buffer,0,minOf(buffer.size,2_000_001-out.size()));if(n<0)break;out.write(buffer,0,n) }
                            require(out.size()<=2_000_000){"Skill file exceeds 2 MB."};out.toByteArray()
                        }
                    }
                }
                error("Too many Skill redirects.")
            }
            val index=JSONObject(String(fetch(base+"index.json"),Charsets.UTF_8));val skills=index.getJSONArray("skills")
            require(skills.length() in 1..100){"Skill catalog must contain 1–100 entries."}
            val digest=SkillParser.hash(base+index.toString())
            val complete=(0 until skills.length()).all { i ->
                val entry=skills.getJSONObject(i);val name=ExtensionPolicy.id(entry.getString("name"));val paths=entry.getJSONArray("files")
                (0 until paths.length()).all { p -> File(File(target,name),safePath(paths.getString(p))).isFile }
            }
            if(complete && File(target,".capri-catalog").takeIf{it.exists()}?.readText()==digest) {File(root,"$id.error").delete();return JSONObject().put("id",id).put("status","cached")}
            stage.mkdirs();var total=0;var count=0;val names=mutableSetOf<String>()
            for(i in 0 until skills.length()) {
                cancel();val entry=skills.getJSONObject(i);val name=ExtensionPolicy.id(entry.getString("name"));require(names.add(name)){"Duplicate remote Skill ID."}
                val files=entry.getJSONArray("files");val paths=(0 until files.length()).map{safePath(files.getString(it))}
                require(paths.distinct().size==paths.size && ("SKILL.md" in paths || "$name.md" in paths)){"Catalog entry needs one Skill document and unique resource paths."}
                val dir=File(stage,name).apply{mkdirs()}
                for(path in paths) {
                    require(++count<=500){"Skill catalog exceeds 500 files."}
                    val resource=origin.newBuilder().addPathSegment(name).apply{path.split('/').forEach(::addPathSegment)}.build().toString()
                    val bytes=fetch(resource);total+=bytes.size;require(total<=10_000_000){"Skill catalog exceeds 10 MB."}
                    val out=ExtensionPolicy.relative(dir,path);out.parentFile!!.mkdirs();out.writeBytes(bytes)
                }
                SkillParser.parseFile(File(dir,if("SKILL.md" in paths)"SKILL.md" else "$name.md"),source="source:$id")
            }
            File(stage,".capri-catalog").writeText(digest);cancel()
            if(target.exists())check(target.renameTo(backup)){"Cannot stage current Skill cache."}
            if(!stage.renameTo(target)) { if(backup.exists())check(backup.renameTo(target));error("Cannot publish Skill cache.") }
            backup.deleteRecursively();File(root,"$id.error").delete()
            return JSONObject().put("id",id).put("status","updated").put("skills",skills.length()).put("bytes",total)
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception) { root.mkdirs();File(root,"$id.error").writeText("Refresh failed; previous complete cache retained.");return JSONObject().put("id",id).put("status","error").put("message","Skill source refresh failed; previous complete cache retained.") }
        finally {stage.deleteRecursively();if(backup.exists()&&!target.exists())backup.renameTo(target)}
    }
    companion object {
        fun validateUrl(value:String):String {val u=value.toHttpUrl();require(u.scheme=="https"&&u.username.isEmpty()&&u.password.isEmpty()&&u.query==null&&u.fragment==null){"Skill sources require HTTPS without credentials, queries or fragments."};return u.toString()}
        fun safePath(path:String):String {
            require(path.isNotBlank()&&!path.contains('\\')&&!path.contains('\u0000')&&!path.contains('?')&&!path.contains('#')&&!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(path)&&!path.startsWith('/')){"Invalid Skill resource path."}
            path.split('/').forEach{part->val decoded=java.net.URLDecoder.decode(part,"UTF-8");require(part.isNotBlank()&&decoded !in setOf(".","..")&&!decoded.contains('/')&&!decoded.contains('\\')&&!decoded.contains('\u0000')){"Invalid Skill resource segment."}}
            return path
        }
    }
}
