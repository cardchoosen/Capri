package ai.capri.app.extensions

import ai.capri.app.RuntimeFiles
import ai.capri.app.knowledge.MemoryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal interface SkillHistory {
    fun skillVersions(id: String): JSONArray
    fun compareSkill(id: String,before: String,after: String): JSONObject
    fun restoreSkill(id: String,revision: String,expected: String): String
}

/** Immutable content snapshots shared by legacy imports and the progressive Skill library. */
internal class SkillVersions(private val root: File) {
    companion object {
        private val lock=Any()
        fun files(directory: File): List<File> {
            val base=directory.canonicalFile;require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "Skill directory must be regular." }
            var bytes=0L;var count=0
            return base.walkTopDown().onEnter { dir -> require(!Files.isSymbolicLink(dir.toPath())) { "Skill snapshots reject symlinks." };dir==base || dir.name !in setOf(".git","node_modules",".venv","__pycache__") }
                .filter { it!=base }.onEach { file -> require(++count<=500 && !Files.isSymbolicLink(file.toPath())) { "Skill snapshot resource limit or symlink violation." };if(file.isFile) { bytes+=file.length();require(bytes<=10_000_000) { "Skill snapshot exceeds 10 MB." } } }
                .filter { it.isFile && !it.name.startsWith(".capri-") }.sortedBy { it.relativeTo(base).invariantSeparatorsPath }.toList()
        }
        fun requireExpected(directory: File,expected: String) { require(revision(directory)==expected) { "Skill revision conflict; inspect the current definition before restoring." } }
        fun revision(directory: File): String {
            val all=files(directory);val entry=File(directory,"SKILL.md");require(entry.isFile && entry.length()<=128000) { "SKILL.md is absent or too large." }
            return MemoryPolicy.hash(buildString { append(entry.readText());all.filter { it.canonicalFile!=entry.canonicalFile }.forEach { append('\n').append(it.relativeTo(directory.canonicalFile).invariantSeparatorsPath).append(':').append(RuntimeFiles.sha(it)) } })
        }
    }
    private fun id(id: String)=ExtensionPolicy.id(id)
    private fun location(id: String,revision: String): File { require(revision.matches(Regex("[a-f0-9]{64}"))) { "Invalid Skill revision." };return File(root,"${id(id)}/$revision") }
    fun capture(id: String,directory: File,origin: String,parent: String=""): String = synchronized(lock) {
        val version=revision(directory);val target=location(id,version)
        if(target.exists()) { require(revision(File(target,id))==version) { "Skill revision snapshot is damaged." };return@synchronized version }
        val stage=File(root,".stage-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val copy=File(stage,id).apply { mkdirs() }
            for(file in files(directory)) { val dest=File(copy,file.relativeTo(directory.canonicalFile).invariantSeparatorsPath);dest.parentFile!!.mkdirs();file.copyTo(dest);dest.setExecutable(file.canExecute(),true) }
            require(revision(copy)==version && revision(directory)==version) { "Skill changed during snapshot; update was not published." }
            File(stage,"record.json").writeText(JSONObject().put("revision",version).put("parent",parent).put("origin",origin).put("created_at",System.currentTimeMillis()).toString())
            target.parentFile!!.mkdirs();Files.move(stage.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
            val snapshots=root.listFiles().orEmpty().filter { !it.name.startsWith('.') }.flatMap { folder -> folder.listFiles().orEmpty().filter { it.isDirectory }.toList() }.sortedBy { it.lastModified() }
            var bytes=snapshots.sumOf { f -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            var same=snapshots.count { it.parentFile?.name==id }
            for(old in snapshots) {
                if(bytes<=64L*1024*1024 && same<=8)break
                if(old==target || old.name==parent && old.parentFile?.name==id)continue
                if(bytes<=64L*1024*1024 && old.parentFile?.name!=id)continue
                val size=old.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                check(old.deleteRecursively()) { "Unable to retire old Skill revision." };bytes-=size;if(old.parentFile?.name==id)same--
            }
            version
        } finally { stage.deleteRecursively() }
    }
    fun list(id: String,current: String): JSONArray = synchronized(lock) { JSONArray(File(root,id(id)).listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }.sortedByDescending { it.lastModified() }.take(8).map { folder -> JSONObject(File(folder,"record.json").readText()).put("current",folder.name==current) }) }
    fun source(id: String,version: String): File = synchronized(lock) { File(location(id,version),id).also { require(it.isDirectory && revision(it)==version) { "Skill revision is missing or damaged." } } }
    fun compare(id: String,before: String,after: String): JSONObject = synchronized(lock) {
        fun manifest(rev: String)=source(id,rev).let { dir -> files(dir).associate { it.relativeTo(dir.canonicalFile).invariantSeparatorsPath to RuntimeFiles.sha(it) } }
        val a=manifest(before);val b=manifest(after)
        JSONObject().put("before",before).put("after",after).put("files",JSONArray((a.keys+b.keys).sorted().filter { a[it]!=b[it] }.map { path -> JSONObject().put("path",path).put("before_sha256",a[path] ?: JSONObject.NULL).put("after_sha256",b[path] ?: JSONObject.NULL) })).put("notice","Content hashes compare all retained resources; no scripts were executed.")
    }
}
