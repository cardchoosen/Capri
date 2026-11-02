package ai.capri.app.agent.delegation

import ai.capri.app.Workspace
import ai.capri.app.knowledge.MemoryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** A declared-file snapshot, not a clone of HOME, a Git checkout or an OS sandbox. No child processes. */
internal object DelegateWorkspace {
    private val excluded=setOf(".git",".venv","node_modules",".ssh",".aws",".config",".capri")
    fun normalize(path: String): String {
        require(path.length in 1..240 && !File(path).isAbsolute && '\\' !in path && '\u0000' !in path) { "Delegated paths must be bounded workspace-relative paths." }
        val parts=path.split('/');require(parts.all { it.isNotBlank() && it!="." && it!=".." && it !in excluded }) { "Delegated path crosses a private/dependency directory or contains traversal." }
        return parts.joinToString("/")
    }
    fun resolve(root: File,path: String): File {
        var file=root
        require(!Files.isSymbolicLink(root.toPath())) { "Workspace root cannot be a symlink." }
        for(part in normalize(path).split('/')) { file=File(file,part);require(!Files.isSymbolicLink(file.toPath())) { "Delegated paths cannot follow symlinks." } }
        require(file.canonicalPath.startsWith(root.canonicalPath+File.separator)) { "Delegated path escaped its workspace." };return file
    }
    fun bytes(root: File,path: String,maximum: Int=524288): ByteArray? {
        val file=resolve(root,path)
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return null
        require(Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS) && file.length()<=maximum) { "Delegated input is not a bounded regular file." }
        val bytes=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS).use { input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=maximum) { "Delegated file grew beyond its byte limit." };out.write(buffer,0,n) };out.toByteArray()
        }
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in text) { "Delegated file inputs must be UTF-8 text, not binary data." };return bytes
    }
    fun hash(bytes: ByteArray?)=bytes?.let { MemoryPolicy.hash(String(it,Charsets.UTF_8)) }
    fun atomic(file: File,value: JSONObject) {
        file.parentFile!!.mkdirs();val tmp=File(file.parentFile,file.name+".tmp")
        java.io.FileOutputStream(tmp).use { it.write(value.toString().toByteArray());it.fd.sync() }
        Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    fun create(source: File,task: File,inputs: List<String>,writes: Set<String>,check: ()->Unit): JSONObject {
        val paths=(inputs+writes).map(::normalize).toMutableSet();require(paths.size<=32) { "A child may snapshot at most 32 declared files." }
        val rules=linkedSetOf<String>()
        for(path in paths.toList()) {
            var parent=File(path).parentFile
            rules+="AGENTS.md"
            while(parent!=null) { rules+=parent.invariantSeparatorsPath+"/AGENTS.md";parent=parent.parentFile }
        }
        // Missing rule paths are recorded too, so adding a rule invalidates a later merge.
        require(paths.size+rules.size<=64) { "Too many scoped rule paths; narrow the assignment." }
        paths+=rules
        val child=Workspace(File(task,"sessions")).folder("child","workspace");val base=File(task,"base").apply { mkdirs() }
        val manifest=JSONArray();var total=0
        for(path in paths.sorted()) {
            check();val data=bytes(source,path)
            require(data!=null || path in writes || path in rules) { "A declared input file is missing." }
            total+=data?.size ?: 0;require(total<=2_000_000) { "A child snapshot exceeds 2 MB; narrow the assignment." }
            if(data!=null) {
                for(target in listOf(resolve(child,path),resolve(base,path))) { target.parentFile!!.mkdirs();target.writeBytes(data) }
            }
            manifest.put(JSONObject().put("path",path).put("sha256",hash(data)?:JSONObject.NULL).put("writable",path in writes).put("rule",path in rules))
        }
        return JSONObject().put("inputs",manifest).put("snapshot_bytes",total)
    }
    fun root(task: File)=Workspace(File(task,"sessions")).folder("child","workspace")
    fun artifacts(task: File,manifest: JSONObject): JSONArray {
        val source=root(task);val inputs=manifest.getJSONArray("inputs");val changes=JSONArray();var total=0
        for(i in 0 until inputs.length()) {
            val before=inputs.getJSONObject(i);if(!before.getBoolean("writable"))continue
            val path=before.getString("path");val data=bytes(source,path,1_000_000);val after=hash(data)
            require(data!=null || before.isNull("sha256")) { "Deleting parent files is not supported by delegated proposals." }
            if(after==before.optString("sha256").takeUnless { before.isNull("sha256") })continue
            total+=data?.size ?: 0;require(total<=2_000_000) { "Changed delegated artifacts exceed 2 MB." }
            changes.put(JSONObject().put("path",path).put("before_sha256",before.opt("sha256")).put("after_sha256",after?:JSONObject.NULL).put("bytes",data?.size?:0))
        }
        return changes
    }
    fun preflight(parent: File,task: File,manifest: JSONObject,changes: JSONArray) {
        val inputs=manifest.getJSONArray("inputs")
        for(i in 0 until inputs.length()) {
            val input=inputs.getJSONObject(i);val current=hash(bytes(parent,input.getString("path")))
            require(current==input.optString("sha256").takeUnless { input.isNull("sha256") }) { "Merge conflict: an input or scoped rule changed; preserve parent edits and reassess the proposal." }
        }
        for(i in 0 until changes.length()) {
            val file=changes.getJSONObject(i);require(hash(bytes(root(task),file.getString("path"),1_000_000))==file.getString("after_sha256")) { "Delegated artifact changed after its receipt was published." }
        }
    }
}
