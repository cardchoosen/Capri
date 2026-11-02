package ai.capri.app.extensions.hub

import ai.capri.app.agent.reliability.DurableJson
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Download one immutable catalog fully before publishing a new cache pointer. Failed sync retains the old cache. */
internal class HubCatalog(private val root:File,private val remote:HubRemote) {
    private val state=DurableJson(File(root,"state.json"))
    fun status():JSONObject=state.access { JSONObject(it.toString()) }
    fun sources():List<File> {
        val commit=status().optString("commit");if(!commit.matches(Regex("[a-f0-9]{40}")))return emptyList()
        return File(root,"$commit/skills").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
    }
    private fun intact(commit:String):Boolean=runCatching {
        val base=File(root,commit);val index=JSONObject(File(base,"index.json").readText());val skills=index.getJSONArray("skills");require(index.getInt("schema")==1 && skills.length()<=100)
        var total=0;var count=0
        for(i in 0 until skills.length()) {
            val entry=skills.getJSONObject(i);val id=HubPackage.id(entry.getString("id"));val files=entry.getJSONArray("files");require(files.length() in 1..50);val values=linkedMapOf<String,ByteArray>()
            for(j in 0 until files.length()) {
                val item=files.getJSONObject(j);val path=HubPackage.path(item.getString("path"));val file=File(base,"skills/$id/$path")
                require(file.isFile && file.length()<=2_000_000 && !java.nio.file.Files.isSymbolicLink(file.toPath()));val bytes=file.readBytes();total+=bytes.size;count++
                require(total<=10_000_000 && count<=500 && bytes.size==item.getInt("bytes") && HubPackage.hash(bytes)==item.getString("sha256"));values[path]=bytes
            }
            require(HubPackage(id,values).digest==entry.getString("revision"));HubPackage.metadata(File(base,"skills/$id"))
        }
    }.isSuccess
    suspend fun sync():JSONObject {
        val repo=remote.api("GET","/repos/${HubGitHub.REPOSITORY}");require(!repo.getBoolean("private") && !repo.optBoolean("archived")) { "The configured public Skill repository is unavailable." }
        val branch=repo.getString("default_branch");require(branch.matches(Regex("[A-Za-z0-9._/-]{1,100}")))
        val ref=remote.api("GET","/repos/${HubGitHub.REPOSITORY}/git/ref/heads/$branch");val commit=ref.getJSONObject("object").getString("sha")
        require(commit.matches(Regex("[a-f0-9]{40}")))
        if(status().optString("commit")==commit && intact(commit))return status().put("status","cached")
        val stage=File(root,"stage-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val raw=remote.raw(commit,"index.json");val index=JSONObject(raw.toString(Charsets.UTF_8));require(index.getInt("schema")==1)
            val skills=index.getJSONArray("skills");require(skills.length()<=100);var count=0;var total=0;val names=mutableSetOf<String>()
            for(i in 0 until skills.length()) {
                val skill=skills.getJSONObject(i);val id=HubPackage.id(skill.getString("id"));require(names.add(id))
                val files=skill.getJSONArray("files");require(files.length() in 1..50);val paths=mutableSetOf<String>();val contents=linkedMapOf<String,ByteArray>()
                for(j in 0 until files.length()) {
                    val entry=files.getJSONObject(j);val path=HubPackage.path(entry.getString("path"));require(paths.add(path) && ++count<=500)
                    val data=remote.raw(commit,"skills/$id/$path");total+=data.size;require(total<=10_000_000 && data.size==entry.getInt("bytes") && HubPackage.hash(data)==entry.getString("sha256")) { "Public Skill integrity or size check failed." }
                    contents[path]=data;val out=File(stage,"skills/$id/$path");out.parentFile!!.mkdirs();out.writeBytes(data)
                }
                HubPackage.metadata(File(stage,"skills/$id"))
                require("SKILL.md" in contents && HubPackage(id,contents).digest==skill.getString("revision")) { "Public Skill revision does not match its resources." }
            }
            File(stage,"index.json").writeBytes(raw)
            val target=File(root,commit);if(target.exists())target.deleteRecursively();check(stage.renameTo(target)) { "Cannot publish the complete public catalog." }
            val result=state.access { it.put("commit",commit).put("synced_at",System.currentTimeMillis()).put("skill_count",skills.length()).put("status","ok");JSONObject(it.toString()) }
            root.listFiles().orEmpty().filter{it.isDirectory && it.name.matches(Regex("[a-f0-9]{40}")) && it.name!=commit}.sortedByDescending{it.lastModified()}.drop(1).forEach{it.deleteRecursively()}
            return result
        } finally { stage.deleteRecursively() }
    }
}
