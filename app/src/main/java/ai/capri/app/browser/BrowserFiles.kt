package ai.capri.app.browser

import ai.capri.app.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.CookieManager
import android.util.AtomicFile
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object BrowserFilePolicy {
    const val MAX_FILE = 20_000_000L
    const val MAX_TOTAL = 100_000_000L
    fun name(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^\\p{L}\\p{N}._-]"),"_").take(120).takeUnless { it.isBlank() || it in setOf(".","..") } ?: "download.bin"
    fun accepts(mime: String, name: String, accepts: List<String>): Boolean = accepts.isEmpty() || accepts.any {
        val a=it.trim().lowercase()
        a.isBlank() || a=="*/*" || (a.startsWith(".") && name.lowercase().endsWith(a)) || a==mime || (a.endsWith("/*") && mime.startsWith(a.removeSuffix("*")))
    }
    fun source(workspace: Workspace, chat: String, raw: String): File {
        val file=workspace.file(chat,Attachments.relativePath(raw))
        require(file.isFile && file.length() in 1..MAX_FILE) { "上传文件不存在、为空或超过20MB" }
        return file
    }
}

/** App-private files and bounded GET transfers; page credentials never enter model results or receipts. Main-thread owner. */
internal class BrowserFiles(private val context: Context, private val workspace: Workspace, private val scope: CoroutineScope,
                            private val debug: Boolean, private val changed: () -> Unit) {
    private data class Transfer(val chat: String, val id: String, val page: String, val request: String,
        var state: String="queued", @Volatile var bytes: Long=0, var path: String="", var mime: String="", var error: String="",
        var job: Job?=null, @Volatile var call: Call?=null) {
        fun json()=JSONObject().put("download_id",id).put("page_id",page).put("request_id",request)
            .put("state",state).put("bytes",bytes).put("path",path).put("mime",mime).put("message",error)
    }
    private val loadLocks=mutableMapOf<String,kotlinx.coroutines.sync.Mutex>()
    private val records=linkedMapOf<String,MutableList<Transfer>>()
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build()
    private var saveSequence=0L
    private val savedSequences=mutableMapOf<String,Long>() // Access only under diskLock.
    private val diskLock=kotlinx.coroutines.sync.Mutex()
    private fun index(chat:String)=AtomicFile(File(workspace.folder(chat,"browser"),"downloads.json"))
    private suspend fun load(chat:String):MutableList<Transfer> {
        val lock=loadLocks.getOrPut(chat){kotlinx.coroutines.sync.Mutex()};lock.lock()
        try {
        records[chat]?.let{return it}
        val loaded=withContext(Dispatchers.IO) {
            val privateUploads=File(workspace.folder(chat,"browser"),"uploads")
            check(!Files.isSymbolicLink(privateUploads.toPath())) { "上传快照目录异常" }
            if(privateUploads.exists())privateUploads.deleteRecursively()
            val artifact=workspace.file(chat,"browser/.recovery").parentFile!!
            if(artifact.isDirectory)artifact.listFiles().orEmpty().filter{it.name.endsWith(".part")&&!Files.isSymbolicLink(it.toPath())}.forEach{it.delete()}
            val f=index(chat)
            if(!f.baseFile.exists())mutableListOf() else {
                val text=f.openRead().bufferedReader().use{it.readText()};check(text.length<=65536)
                val a=JSONArray(text)
                (0 until a.length().coerceAtMost(32)).map { i -> val r=a.getJSONObject(i)
                    Transfer(chat,r.getString("download_id"),r.getString("page_id"),r.optString("request_id"),
                        r.getString("state").let{if(it in setOf("queued","running"))"interrupted" else it},r.optLong("bytes"),r.optString("path"),r.optString("mime"),r.optString("message"))
                }.toMutableList()
            }
        }
        return records.getOrPut(chat){loaded}
        } finally{lock.unlock()}
    }
    private suspend fun save(chat:String) {
        val sequence=++saveSequence
        val data=JSONArray(records[chat].orEmpty().takeLast(32).map{it.json()}).toString()
        withContext(Dispatchers.IO) {
            diskLock.lock()
            try { if(sequence<=(savedSequences[chat] ?: -1L))return@withContext
                val f=index(chat);val out=f.startWrite();try{out.write(data.toByteArray());f.finishWrite(out);savedSequences[chat]=sequence}catch(e:Exception){f.failWrite(out);throw e} }
            finally{diskLock.unlock()}
        }
    }
    suspend fun prepare(chat:String) { load(chat) }
    suspend fun list(chat:String):JSONObject=JSONObject().put("status","ok").put("downloads",JSONArray(load(chat).map{it.json()}))
    suspend fun cancel(chat:String,id:String):JSONObject {
        val t=load(chat).firstOrNull{it.id==id} ?: throw BrowserFault("invalid_argument","下载不属于当前聊天")
        if(t.state in setOf("running","queued")){t.state="cancelled";t.call?.cancel();t.job?.cancel();save(chat);changed()}
        return t.json().put("status","ok")
    }
    fun closePage(page:String) { records.values.flatten().filter{it.page==page&&it.state in setOf("running","queued")}.forEach { it.state="interrupted";it.call?.cancel();it.job?.cancel() } }
    suspend fun deleteChat(chat:String) {
        val transfers=records.remove(chat).orEmpty()
        transfers.forEach { it.call?.cancel();it.job?.cancel() }
        transfers.mapNotNull { it.job }.forEach { it.join() }
    }
    fun dispose(){records.values.flatten().forEach{it.call?.cancel();it.job?.cancel()}}
    private fun output(chat:String,filename:String):File {
        val dir=workspace.file(chat,"browser/$filename")
        check(!Files.isSymbolicLink(dir.parentFile!!.toPath())) { "输出目录不能是符号链接" }
        dir.parentFile!!.mkdirs();return dir
    }
    private fun commit(chat:String,part:File,name:String):File {
        val target=output(chat,name)
        val total=target.parentFile!!.listFiles().orEmpty().filter{it.isFile&&!it.name.endsWith(".part")}.sumOf{it.length()}
        check(part.length()<=BrowserFilePolicy.MAX_FILE && total+part.length()<=BrowserFilePolicy.MAX_TOTAL) { "浏览器文件超过单文件20MB或目录100MB限额" }
        check(!target.exists()&&part.renameTo(target)) { "文件落盘失败" };return target
    }
    suspend fun screenshot(chat:String,web:WebView):JSONObject {
        load(chat)
        val lease=RuntimeGate.beginFiles(context,chat,"浏览器截图")
        var bitmap:Bitmap?=null
        var part:File?=null
        try {
            check(web.width>0&&web.height>0&&web.isAttachedToWindow) { "网页视口未就绪" }
            val scale=minOf(1.0,1440.0/web.width,kotlin.math.sqrt(4_000_000.0/(web.width.toDouble()*web.height)))
            bitmap=Bitmap.createBitmap((web.width*scale).toInt().coerceAtLeast(1),(web.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.scale(scale.toFloat(),scale.toFloat());web.draw(canvas)
            val b=bitmap;val name="${UUID.randomUUID()}.png"
            val result=withContext(Dispatchers.IO){
                part=output(chat,"$name.part")
                part!!.outputStream().use{check(b.compress(Bitmap.CompressFormat.PNG,100,it))}
                diskLock.lock();try { commit(chat,part!!,name) }finally{diskLock.unlock()}
            }
            return JSONObject().put("status","ok").put("path","browser/${result.name}").put("mime","image/png")
                .put("width",b.width).put("height",b.height).put("bytes",result.length()).put("scope","viewport")
        }finally{bitmap?.recycle();part?.delete();RuntimeGate.endFiles(lease)}
    }
    suspend fun stage(chat:String,paths:JSONArray):List<File> = withContext(Dispatchers.IO) {
        val source=(0 until paths.length()).map{BrowserFilePolicy.source(workspace,chat,paths.getString(it))}
        check(source.sumOf{it.length()}<=BrowserFilePolicy.MAX_FILE) { "上传合计超过20MB" }
        val root=File(workspace.folder(chat,"browser"),"uploads/${UUID.randomUUID()}")
        check(!Files.isSymbolicLink(root.parentFile!!.toPath()))
        var total=0L
        try { source.mapIndexed { i,f -> File(root,"$i/${BrowserFilePolicy.name(f.name)}").also { dest->
            dest.parentFile!!.mkdirs();f.inputStream().use{input->dest.outputStream().use{out->val buffer=ByteArray(32768);var count=0L
                while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;count+=n;total+=n;check(total<=BrowserFilePolicy.MAX_FILE);out.write(buffer,0,n)}
            }}
        } } }catch(e:Exception){root.deleteRecursively();throw e}
    }
    suspend fun start(chat:String,page:String,url:String,request:String,cookies:CookieManager,userAgent:String):JSONObject {
        val all=load(chat)
        all.firstOrNull{it.request==request}?.let{return it.json().put("status","duplicate_not_replayed")}
        check(records.values.flatten().count{it.state in setOf("queued","running")}<2){"最多同时下载2个文件"}
        BrowserContract.url(url,debug)
        val lease=RuntimeGate.beginFiles(context,chat,"浏览器下载")
        val t=Transfer(chat,UUID.randomUUID().toString(),page,request)
        all.add(t);while(all.size>32)all.removeAt(all.indexOfFirst{it.state !in setOf("queued","running")}.also{check(it>=0)})
        try{save(chat)}catch(e:Exception){all.remove(t);RuntimeGate.endFiles(lease);throw e}
        if(!scope.isActive){t.state="interrupted";RuntimeGate.endFiles(lease);throw BrowserFault("interrupted","浏览器已退出，不启动下载")}
        t.job=scope.launch {
            var committed:File?=null
            var part:File?=null
            try {
                t.state="running";save(chat);changed()
                val result=withTimeout(120_000) { withContext(Dispatchers.IO){
                    var current=BrowserContract.url(url,debug);var count=0L
                    for(hop in 0..5){
                        ensureActive()
                        val cookie=withContext(Dispatchers.Main){cookies.getCookie(current)}
                        val builder=Request.Builder().url(current).get().header("User-Agent",userAgent)
                        if(!cookie.isNullOrBlank())builder.header("Cookie",cookie)
                        val call=client.newCall(builder.build());t.call=call;ensureActive()
                        val response=call.execute()
                        response.use { r ->
                            if(r.isRedirect){
                                check(hop<5){"重定向次数超限"}
                                current=BrowserContract.url(current.toHttpUrl().resolve(r.header("Location").orEmpty())?.toString() ?: error("重定向无效"),debug)
                            } else {
                                check(r.isSuccessful){"下载HTTP ${r.code}"}
                                val body=r.body ?: error("下载无内容")
                                check(body.contentLength()<=BrowserFilePolicy.MAX_FILE){"文件超过20MB"}
                                val name="${t.id}-${BrowserFilePolicy.name(android.webkit.URLUtil.guessFileName(current,r.header("Content-Disposition"),r.header("Content-Type")))}"
                                part=output(chat,"$name.part")
                                body.byteStream().use{input->part!!.outputStream().use{out->val buffer=ByteArray(32768)
                                    while(true){ensureActive();val n=input.read(buffer);if(n<0)break;count+=n;t.bytes=count;check(count<=BrowserFilePolicy.MAX_FILE){"文件超过20MB"};out.write(buffer,0,n)}
                                }}
                                check(body.contentLength()<0||body.contentLength()==count){"下载内容不完整"}
                                ensureActive();diskLock.lock()
                                val file=try{commit(chat,part!!,name).also{committed=it}}finally{diskLock.unlock()}
                                return@withContext Triple(file,count,r.header("Content-Type").orEmpty().substringBefore(';'))
                            }
                        }
                    }
                    error("重定向无效")
                } }
                t.bytes=result.second;t.path="browser/${result.first.name}";t.mime=result.third;t.state="completed"
            }catch(e:CancellationException){if(t.state!="cancelled")t.state="interrupted";throw e}
            catch(e:Exception){t.state="failed";t.error=when(e){is javax.net.ssl.SSLException->"下载TLS校验失败";is java.net.UnknownHostException->"下载DNS解析失败";is java.net.SocketTimeoutException->"下载网络超时";is IllegalStateException->e.message?.take(160) ?: "文件或HTTP校验失败";else->"下载未完成（${e.javaClass.simpleName}），检查网络、HTTP或文件限额，不自动重试"}}
            finally{t.call?.cancel();withContext(NonCancellable+Dispatchers.IO){part?.delete();if(t.state!="completed")committed?.delete()};withContext(NonCancellable){runCatching{save(chat)};RuntimeGate.endFiles(lease);changed()}}
        }
        t.job!!.invokeOnCompletion { RuntimeGate.endFiles(lease) }
        return t.json().put("status","accepted")
    }
}
