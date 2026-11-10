package ai.capri.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Turn-scoped final deliveries. Tool success queues a verified version; it does not publish drafts. */
internal object ArtifactDelivery {
    const val KEY="_capri_artifacts"
    class Changed:IllegalStateException("Queued artifact changed or disappeared. Validate the final file and call send_file again; do not deliver an obsolete version.")
    fun clear(messages:JSONArray){messages.getJSONObject(0).remove(KEY)}
    fun pending(messages:JSONArray):List<JSONObject> = messages.getJSONObject(0).optJSONArray(KEY)?.let{a->(0 until a.length()).map{JSONObject(a.getJSONObject(it).toString())}}?:emptyList()
    fun stage(messages:JSONArray,workspace:Workspace,chat:String,path:String,caption:String):JSONObject {
        val item=Attachments.inspect(workspace,chat,path,caption)
        item.put("sha256",RuntimeFiles.sha(workspace.file(chat,item.getString("path")))).put("delivery_id",UUID.randomUUID().toString())
        val all=pending(messages).filterNot{it.getString("path")==item.getString("path")}
        require(all.size<8){"At most eight final artifacts may be queued per turn."}
        messages.getJSONObject(0).put(KEY,JSONArray(all+item))
        return JSONObject().put("status","ok").put("attached",false).put("delivery","queued_final").put("file",item)
            .put("notice","Queued only. Finish all required checks before delivery. Requeue this path after any change; only its latest queued version will be delivered with the final reply.")
    }
    fun issue(messages:JSONArray,workspace:Workspace,chat:String):String? = pending(messages).firstNotNullOfOrNull { item ->
        val file=runCatching{workspace.file(chat,item.getString("path"))}.getOrNull()
        if(file==null||!file.isFile||file.length()!=item.getLong("size")||file.lastModified()!=item.getLong("modified_at"))Changed().message else null
    }
    fun prepare(messages:JSONArray,workspace:Workspace,chat:String):List<JSONObject> = pending(messages).map { item ->
        val source=runCatching{workspace.file(chat,item.getString("path"))}.getOrNull()?:throw Changed()
        if(!source.isFile||source.length()!=item.getLong("size")||RuntimeFiles.sha(source)!=item.getString("sha256"))throw Changed()
        val target=workspace.file(chat,"deliveries/${item.getString("delivery_id")}/${source.name}")
        target.parentFile!!.mkdirs()
        if(!target.exists())try {
            source.inputStream().use{input->target.outputStream().use{output->
                val buffer=ByteArray(65536);var remaining=item.getLong("size")
                while(remaining>0){val count=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());if(count<=0)throw Changed();output.write(buffer,0,count);remaining-=count}
                if(input.read()!=-1)throw Changed()
            }}
        }catch(error:Exception){target.delete();throw error}
        if(target.length()!=item.getLong("size")||RuntimeFiles.sha(target)!=item.getString("sha256")){target.delete();throw Changed()}
        Attachments.inspect(workspace,chat,target.relativeTo(workspace.folder(chat,"workspace").canonicalFile).invariantSeparatorsPath,item.optString("caption"))
            .put("delivery_id",item.getString("delivery_id")).put("sha256",item.getString("sha256")).put("source_path",item.getString("path"))
    }
    fun publish(entries:MutableList<Entry>,items:List<JSONObject>) {
        val delivered=entries.filter{it.role=="attachment"}.mapNotNull{runCatching{JSONObject(it.text).optString("delivery_id")}.getOrNull()}.toSet()
        items.filter{it.getString("delivery_id") !in delivered}.forEach{entries.add(Entry("attachment",it.toString()))}
    }
}
