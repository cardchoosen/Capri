package ai.capri.app.extensions.hub

import ai.capri.app.extensions.ExtensionPolicy
import ai.capri.app.extensions.SkillVersions
import ai.capri.app.knowledge.MemoryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Immutable, bounded public proposal. No chat, account, environment or execution output is collected. */
internal data class HubPackage(val id:String,val files:Map<String,ByteArray>) {
    val digest get()=hash(files.toSortedMap().entries.joinToString("\n") { "${it.key}:${hash(it.value)}" }.toByteArray())
    fun manifest()=JSONObject().put("id",id).put("revision",digest).put("files",JSONArray(files.toSortedMap().map { (path,bytes)->JSONObject().put("path",path).put("sha256",hash(bytes)).put("bytes",bytes.size) }))
    companion object {
        fun id(value:String):String { require(value.length<=64 && value.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*"))) { "Skill ID must contain lowercase ASCII letters, digits and single hyphens, at most 64 characters." };return value }
        fun metadata(source:File):ai.capri.app.extensions.SkillInfo = try { ai.capri.app.extensions.SkillParser.parse(source) }catch(error:Exception) { throw IllegalArgumentException("Invalid Skill metadata; inspect SKILL.md and its resource limits.") }
        fun read(input:java.io.InputStream,maximum:Int):ByteArray {
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(out.size()<=maximum) { val n=input.read(buffer,0,minOf(buffer.size,maximum+1-out.size()));if(n<0)break;out.write(buffer,0,n) }
            require(out.size()<=maximum) { "Skill bytes exceed their limit." };return out.toByteArray()
        }
        fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun path(value:String):String {
            require(value.length in 1..240 && !value.startsWith('/') && '\\' !in value && value.all{it.code in 32..126}) { "Invalid public Skill path." }
            require(value.split('/').all { it.isNotBlank() && it!="." && it!=".." && !it.startsWith('.') && it !in setOf("node_modules","__pycache__") }) { "Public Skill resources cannot contain hidden or dependency paths." }
            return value
        }
        fun capture(source:File,id:String):HubPackage {
            id(id);val parsed=metadata(source)
            require(parsed.name==id && parsed.description.isNotBlank() && '\n' !in parsed.description) { "Public Skills need a portable name and a nonempty single-line description." }
            val before=SkillVersions.revision(source);val entries=SkillVersions.files(source);require(entries.size in 1..50) { "Public contributions require 1..50 files." }
            val files=linkedMapOf<String,ByteArray>();var total=0
            for(file in entries) {
                val name=path(file.relativeTo(source.canonicalFile).invariantSeparatorsPath)
                require(name.substringAfterLast('.',"") in setOf("md","txt","py","js","ts","json","yaml","yml","sh","toml","csv")) { "This public contribution flow accepts text resources only." }
                val bytes=Files.newInputStream(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS).use { read(it,1_000_000) }
                total+=bytes.size;require(total<=1_000_000) { "Public contribution exceeds 1 MB." }
                val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                require('\u0000' !in text && !MemoryPolicy.credentialValue(text) && !Regex("(?i)(gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)").containsMatchIn(text)) { "Potential credentials or binary content found; prepare a sanitized public copy." }
                files[name]=bytes
            }
            require("SKILL.md" in files) { "Public contribution requires SKILL.md." }
            val doc=files.getValue("SKILL.md").toString(Charsets.UTF_8).replace("\r\n","\n")
            require(Regex("(?m)^name: ?${Regex.escape(id)}\\s*$").containsMatchIn(doc.substringBefore("\n---\n",doc))) { "Use the portable Skill ID as the frontmatter name before contributing." }
            require(SkillVersions.revision(source)==before) { "Skill changed during public preparation; review a fresh snapshot." }
            return HubPackage(id,files)
        }
    }
}
