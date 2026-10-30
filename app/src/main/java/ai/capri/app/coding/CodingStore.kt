package ai.capri.app.coding

import ai.capri.app.HistoryEditing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Independent, bounded edit evidence. Pending records are never automatically evicted or replayed. */
internal class CodingStore(private val root:File) {
    companion object { fun hash(bytes:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)} }
    private fun dir(scope:String):File {val (chat,revision,legacy)=HistoryEditing.scope(scope);return File(File(root,chat),"${if(legacy)"branches" else "revisions"}/${revision ?: "main"}").apply{mkdirs()}}
    private fun id(value:String){require(runCatching { UUID.fromString(value).toString()==value }.getOrDefault(false)){"Invalid snapshot id."}}
    private fun atomic(file:File,bytes:ByteArray){
        val temp=Files.createTempFile(file.parentFile.toPath(),".snapshot-",".tmp")
        try {FileChannel.open(temp,StandardOpenOption.WRITE).use { c->val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())c.write(buffer);c.force(true) };Files.move(temp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally{Files.deleteIfExists(temp)}
    }
    @Synchronized fun reserve(scope:String,bytes:Long,count:Int){
        val directory=dir(scope)
        fun size()=directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
        fun records()=directory.listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.lastModified() }
        val candidates=records().toMutableList()
        while(size()+bytes>64L*1024*1024 || records().size+count>128){
            val victim=candidates.firstOrNull { runCatching{JSONObject(it.readText()).optString("state")=="committed"}.getOrDefault(false) } ?: error("Snapshot budget exhausted by pending changes; inspect them before writing.")
            candidates.remove(victim);val name=victim.nameWithoutExtension
            listOf("before","after","json").forEach { Files.deleteIfExists(File(directory,"$name.$it").toPath()) }
        }
    }
    @Synchronized fun stage(scope:String,path:String,before:ByteArray?,after:ByteArray?,mode:String?,operation:String):JSONObject {
        reserve(scope,(before?.size ?: 0).toLong()+(after?.size ?: 0)+8192,1)
        val id=UUID.randomUUID().toString();val directory=dir(scope)
        before?.let{atomic(File(directory,"$id.before"),it)};after?.let{atomic(File(directory,"$id.after"),it)}
        val record=JSONObject().put("id",id).put("path",path).put("state","pending").put("operation_id",operation)
            .put("before_sha256",before?.let(::hash) ?: "absent").put("after_sha256",after?.let(::hash) ?: "absent").put("mode",mode ?: JSONObject.NULL)
        atomic(File(directory,"$id.json"),record.toString().toByteArray());return record
    }
    @Synchronized fun commit(scope:String,record:JSONObject):JSONObject {
        record.put("state","committed")
        val before=content(scope,record,"before");val after=content(scope,record,"after")
        record.put("quick_diagnostics",QuickDiagnostics.delta(QuickDiagnostics.inspect(record.getString("path"),before?.toString(Charsets.UTF_8)),QuickDiagnostics.inspect(record.getString("path"),after?.toString(Charsets.UTF_8))))
        atomic(File(dir(scope),"${record.getString("id")}.json"),record.toString().toByteArray());return record
    }
    @Synchronized fun get(scope:String,value:String):JSONObject {id(value);return JSONObject(File(dir(scope),"$value.json").readText())}
    @Synchronized fun content(scope:String,record:JSONObject,side:String):ByteArray? {
        require(side in setOf("before","after"));id(record.getString("id"));if(record.getString("${side}_sha256")=="absent")return null
        val bytes=File(dir(scope),"${record.getString("id")}.$side").readBytes();check(hash(bytes)==record.getString("${side}_sha256")){"Snapshot bytes do not match their recorded version."};return bytes
    }
    @Synchronized fun count(scope:String)=dir(scope).listFiles().orEmpty().count{it.extension=="json"}
    @Synchronized fun list(scope:String)=JSONArray(dir(scope).listFiles().orEmpty().filter{it.extension=="json"}.sortedByDescending { it.lastModified() }.take(32).map { JSONObject(it.readText()).apply{remove("quick_diagnostics")} })
    fun diff(scope:String,value:String):JSONObject {
        val r=get(scope,value);val before=content(scope,r,"before")?.toString(Charsets.UTF_8).orEmpty();val after=content(scope,r,"after")?.toString(Charsets.UTF_8).orEmpty()
        if(before.length.toLong()+after.length>2_000_000)return JSONObject().put("status","partial").put("snapshot_id",value).put("reason","diff_size_limit").put("notice","Snapshot retained; this bounded preview cannot compare the entire file.")
        val a=before.lineSequence().take(100001).toList();val b=after.lineSequence().take(100001).toList()
        if(a.size>100000 || b.size>100000)return JSONObject().put("status","partial").put("snapshot_id",value).put("reason","diff_line_limit")
        var first=0;while(first<minOf(a.size,b.size)&&a[first]==b[first])first++
        var endA=a.size;var endB=b.size;while(endA>first&&endB>first&&a[endA-1]==b[endB-1]){endA--;endB--}
        val from=(first-3).coerceAtLeast(0);val toA=(endA+3).coerceAtMost(a.size);val toB=(endB+3).coerceAtMost(b.size)
        val text="--- ${r.getString("path")} (${r.getString("before_sha256")})\n+++ ${r.getString("path")} (${r.getString("after_sha256")})\n@@ -${from+1},${toA-from} +${from+1},${toB-from} @@\n"+
            a.subList(from,first).joinToString("\n"){" $it"}+"\n"+a.subList(first,endA).take(150).joinToString("\n"){"-$it"}+"\n"+b.subList(first,endB).take(150).joinToString("\n"){"+$it"}+"\n"+b.subList(endB,toB).joinToString("\n"){" $it"}
        return JSONObject().put("status","ok").put("kind","diff").put("snapshot_id",value).put("path",r.getString("path")).put("before_sha256",r.getString("before_sha256")).put("sha256",r.getString("after_sha256"))
            .put("text",text.take(12000)).put("truncated",text.length>12000 || endA-first>150 || endB-first>150).put("notice","Historical snapshot comparison; it does not assert the current workspace still matches. This preview is not an apply_patch input.")
    }
}
