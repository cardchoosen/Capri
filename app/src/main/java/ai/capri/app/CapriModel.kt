package ai.capri.app

import android.app.Application
import android.util.Log
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CapriModel(app: Application) : AndroidViewModel(app) {
    companion object { const val MAX_MODEL_CALLS = 50 }
    internal val gitAccounts = ai.capri.app.git.GitAccounts(app)
    val store = Store(app)
    val runtime = PythonRuntime(app, store.workspace)
    internal val terminals = ai.capri.app.terminal.TerminalManager.get(app)
    internal val capabilityTool = CapabilityTool(app, runtime)
    internal val shortTasks = ShortTaskTool(runtime, capabilityTool)
    internal val pythonEnvironment = ai.capri.app.pythonenv.PythonEnvironmentTool(terminals)
    val androidTools = ai.capri.app.nativebridge.AndroidTool(app)
    val sessions = store.load()
    init { store.save(sessions) }
    var selected = sessions.first().id
    private val tick = MutableStateFlow(0)
    val updates = tick.asStateFlow()
    val jobs = ConcurrentHashMap<String, Job>()
    internal val activeTools = ConcurrentHashMap<String, ToolActivity>()
    val notices = ConcurrentHashMap<String, String>()
    private val recovery = AgentRecovery(File(app.filesDir, "agent-recovery"))
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    var runtimeBusy = false
    var runtimeResult = ""
    var migrating = !LegacySessionReset.marker(app).isFile
        private set
    private var migrationJob: Job? = null
    init {
        viewModelScope.launch { runtime.environment.state.collect { refresh() } }
        viewModelScope.launch { runtime.environment.chats.collect { refresh() } }
        if (migrating) startMigration() else prepareSession(selected)
    }
    private fun startMigration() {
        if (migrationJob?.isActive == true) return
        migrationJob = viewModelScope.launch {
            try {
                // Service keeps the base setup alive; the await shares its single installer lock.
                startPreparationService(selected, true)
                runtime.environment.ensureBase()
                runtime.environment.publish("preparing", "正在清理旧会话环境，保留全局配置")
                terminals.resetForNewEnvironment()
                withContext(Dispatchers.IO) { LegacySessionReset.clear(getApplication()) }
                sessions.clear(); Session().also { sessions.add(it); selected = it.id }
                persist()
                withContext(Dispatchers.IO) { LegacySessionReset.marker(getApplication()).writeText("ubuntu-noble-v1") }
                migrating = false
                prepareSession(selected)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { runtime.environment.publish("error", "环境准备未完成，旧数据尚未完成清理", error.message?.take(1500).orEmpty()) }
            finally { refresh() }
        }
    }
    private fun startPreparationService(chat: String, baseOnly: Boolean = false) {
        androidx.core.content.ContextCompat.startForegroundService(getApplication(),
            android.content.Intent(getApplication(), LinuxSetupService::class.java).putExtra("chat", chat).putExtra("base_only", baseOnly))
    }
    internal fun prepareSession(chat: String) {
        if (migrating) { startMigration(); return }
        runtime.environment.chatState(chat, LinuxState("preparing", "正在准备会话环境"))
        try { startPreparationService(chat) }
        catch (error: Exception) { runtime.environment.chatState(chat, LinuxState("error", "请返回前台重试准备", error.message?.take(500).orEmpty())) }
        refresh()
    }
    internal fun environmentState(): LinuxState {
        val shared = runtime.environment.state.value
        return if (migrating && shared.phase == "ready") LinuxState("preparing", "正在清理旧会话环境，保留全局配置")
        else if (migrating || !runtime.ready) shared
        else runtime.environment.chats.value[selected] ?: LinuxState("preparing", "正在准备会话环境")
    }
    fun installRuntime() = prepareSession(selected)
    fun inspectPythonEnvironment() {
        if (runtimeBusy || migrating) return
        runtimeBusy = true; runtimeResult = "正在检查执行环境…"; refresh()
        val chat = selected
        viewModelScope.launch {
            try { runtimeResult = capabilityTool.execute(chat, JSONObject().put("action", "inspect")) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { runtimeResult = "环境检查未完成：${error.message?.take(1000)}" }
            finally { runtimeBusy = false; refresh() }
        }
    }
    fun refresh() { tick.value++ }
    fun current() = sessions.first { it.id == selected }
    fun create() {
        if (migrating) return
        Session().also { sessions.add(0, it); selected = it.id }; persist(); prepareSession(selected)
    }
    fun select(id: String) { selected = id; if (!runtime.environment.chats.value.containsKey(id)) prepareSession(id); refresh() }
    fun persist() { store.save(sessions); refresh() }
    fun stop() { jobs[selected]?.cancel(); notices[selected] = "已请求停止"; refresh() }
    fun send(text: String): Boolean = sendTo(selected, text)
    internal fun sendTo(chat: String, text: String): Boolean {
        if (text.isBlank() || jobs.containsKey(chat)) return false
        if (migrating) { notices[chat] = "正在准备新执行环境，完成后即可开始新会话"; refresh(); return false }
        val session = sessions.firstOrNull { it.id == chat } ?: return false
        if (IdentityReply.matches(text)) {
            session.messages.add(Entry("user", text.trim()))
            session.messages.forEach { it.reveal?.finish() }
            session.messages.add(Entry("assistant", IdentityReply.TEXT, FinalReplyReveal()))
            if (session.title == "新会话") session.title = text.trim().take(20)
            notices.remove(session.id)
            persist()
            return true
        }
        val config = store.config()
        if (config.model.isBlank() || config.key.isBlank()) { notices[session.id] = "请先在设置中配置模型与 API Key"; refresh(); return false }
        session.messages.forEach { it.reveal?.finish() }
        session.messages.add(Entry("user", text.trim()))
        if (session.title == "新会话") session.title = text.take(20)
        persist()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { respond(session, config) }
            catch (e: CancellationException) { session.messages.add(Entry("status", "执行已停止")); throw e }
            catch (e: ModelRequestFailure) {
                Log.w("CapriModelRequest", "failed category=${e.category} type=${e.cause?.javaClass?.simpleName}")
                session.messages.add(Entry("status", "${e.category}，模型回复未完成。已记录的工具结果保留，持续终端不会因此自动停止。发送“继续”可接续；结果未确认的操作会先核验，不直接重跑。"))
            }
            catch (e: Exception) { session.messages.add(Entry("status", "客户端处理未完成。已有工具操作不会回滚，请查看执行记录；发送“继续”可从检查点核验。")); Log.w("CapriModelRequest", "client_failure type=${e.javaClass.simpleName}") }
            finally { activeTools.remove(session.id); jobs.remove(session.id); notices.remove(session.id); persist() }
        }
        jobs[session.id] = job; notices[session.id] = "思考中"; refresh(); job.start()
        return true
    }
    private fun tool(name: String, description: String, properties: JSONObject, required: List<String>) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description).put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required))))
    private fun props(vararg names: String) = JSONObject().apply { names.forEach { put(it, JSONObject().put("type", "string")) } }
    private suspend fun respond(session: Session, config: ModelConfig) {
        val trace = PerformanceTrace(File(store.workspace.root(session.id), "metrics/${java.util.UUID.randomUUID()}.jsonl"))
        var outcome = PerformanceTrace.Status.ERROR
        try {
        val saved = withContext(Dispatchers.IO) { recovery.load(session.id) }
        val continuing = saved != null && RecoveryMessages.isContinuation(session.messages.last().text)
        val messages = if (saved != null) RecoveryMessages.forResume(saved) else JSONArray()
        val rules = getApplication<Application>().assets.open("persona/core.txt").bufferedReader().use { it.readText() } + "\n" +
            getApplication<Application>().assets.open("runtime/execution_rules.txt").bufferedReader().use { it.readText() } +
            "\n本轮最多 $MAX_MODEL_CALLS 次模型请求（包含网络重试），这是客户端预算；验证任务后及时回复。\n" + ExecutionGuidance.text
        if (saved != null) {
            messages.getJSONObject(0).put("content", rules)
            val instruction = if (continuing) "接续检查点，已记录的工具不得因网络重试重复执行。"
                else "这是新请求；先前记录仅用于核验现存资源，不要自行继续先前目标。"
            messages.put(JSONObject().put("role", "user").put("content", session.messages.last().text + "；" + instruction))
        } else {
            messages.put(JSONObject().put("role", "system").put("content", rules))
            session.messages.filter { it.role in listOf("user", "assistant") }.takeLast(30).forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
        }
        suspend fun checkpoint() = withContext(Dispatchers.IO) { recovery.save(session.id, messages) }
        checkpoint()

        val tools = JSONArray().put(tool("profile_search", "仅在用户询问你自身身份、角色、经历、外观时检索核心档案。", props("query"), listOf("query")))
            .put(tool("list_files", "列出当前会话工作区文件", props(), emptyList()))
            .put(tool("read_file", "读取当前工作区文本文件", props("path"), listOf("path")))
            .put(tool("write_file", "写入当前工作区文本文件", props("path", "content"), listOf("path", "content")))
            .put(tool("send_file", "把当前会话工作区已有文件发送到聊天。PNG/JPEG/GIF/WebP内联显示（GIF可播放）；其他文件可分享导出。最大20MB，不发送私钥或凭据。path为相对路径或/workspace/路径，caption可选。", props("path", "caption"), listOf("path")))
            .put(ShortTaskContract.specification())
            .put(CapabilityContract.specification())
            .put(androidTools.specification())
            .put(ai.capri.app.terminal.TerminalContract.specification())
        val requests = ModelRequests(MAX_MODEL_CALLS)
        while (requests.used < MAX_MODEL_CALLS) {
            currentCoroutineContext().ensureActive()
            notices[session.id] = "思考中"; refresh()
            val response = requests.execute(onRetry = { attempt, category ->
                notices[session.id] = "模型连接重试 $attempt/2"; refresh()
                Log.w("CapriModelRequest", "retry=$attempt used=${requests.used} category=$category")
            }, onBackoff = { elapsed -> trace.event(PerformanceTrace.Stage.MODEL_BACKOFF, elapsed, PerformanceTrace.Status.RETURNED) }) {
                val attemptStarted = PerformanceTrace.now()
                val attempt = requests.used
                var requestStatus = PerformanceTrace.Status.ERROR
                try { withContext(Dispatchers.IO) {
                val url = config.endpoint.trimEnd('/') + "/chat/completions"
                require(url.startsWith("https://")) { "模型接口须使用 HTTPS" }
                val body = JSONObject().put("model", config.model).put("messages", messages).put("tools", tools).put("stream", false)
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val call = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${config.key}").post(body.toString().toRequestBody("application/json".toMediaType())).build())
                suspendCancellableCoroutine<JSONObject> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) {
                            val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
                            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                            Log.w("CapriModelRequest", "phase=transport attempt=${requests.used} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - startedAt} type=${e.javaClass.simpleName} active=${caps != null} validated=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}")
                            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        }
                        override fun onResponse(call: Call, response: Response) {
                            response.use { r ->
                                Log.i("CapriModelRequest", "phase=response attempt=${requests.used} http=${r.code} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                                trace.event(PerformanceTrace.Stage.MODEL_HEADERS, PerformanceTrace.elapsed(attemptStarted), PerformanceTrace.Status.RETURNED, attempt = attempt, http = r.code)
                                val result = runCatching {
                                    if (!r.isSuccessful) throw ModelHttpError(r.code,
                                        r.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 600)?.times(1000))
                                    val bodyStarted = PerformanceTrace.now()
                                    var bodyStatus = PerformanceTrace.Status.ERROR
                                    val raw = try { (r.body?.string() ?: error("空响应")).also { bodyStatus = PerformanceTrace.Status.OK } }
                                    finally { trace.event(PerformanceTrace.Stage.MODEL_BODY, PerformanceTrace.elapsed(bodyStarted), bodyStatus, attempt = attempt) }
                                    val parseStarted = PerformanceTrace.now()
                                    var parseStatus = PerformanceTrace.Status.ERROR
                                    var usage: JSONObject? = null
                                    try {
                                        JSONObject(raw).also {
                                            it.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                                            usage = it.optJSONObject("usage"); parseStatus = PerformanceTrace.Status.OK
                                        }
                                    } finally { trace.event(PerformanceTrace.Stage.MODEL_PARSE, PerformanceTrace.elapsed(parseStarted), parseStatus, attempt = attempt, usage = usage) }
                                }
                                if (continuation.isActive) continuation.resumeWith(result)
                            }
                        }
                    })
                }
            }.also { requestStatus = PerformanceTrace.Status.OK }
                } catch (cancel: CancellationException) { requestStatus = PerformanceTrace.Status.CANCELLED; throw cancel }
                finally { trace.event(PerformanceTrace.Stage.MODEL_REQUEST, PerformanceTrace.elapsed(attemptStarted), requestStatus, attempt = attempt) }
            }
            val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            messages.put(message)
            val content = if (message.isNull("content")) "" else message.optString("content")
            val calls = message.optJSONArray("tool_calls")
            val names = if (calls == null) emptyList() else (0 until calls.length()).map {
                calls.getJSONObject(it).getJSONObject("function").getString("name")
            }
            if (content.isNotBlank() && ToolPresentation.showPreamble(names)) {
                val finalReply = calls == null || calls.length() == 0
                session.messages.add(Entry("assistant", content, if (finalReply) FinalReplyReveal() else null)); persist()
            }
            if (calls == null || calls.length() == 0) {
                withContext(Dispatchers.IO) { recovery.clear(session.id) }
                outcome = PerformanceTrace.Status.OK
                return
            }
            // Persist the proposed calls before dispatch: an interrupted side effect is unknown, never auto-replayed.
            checkpoint()
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val function = call.getJSONObject("function")
                val name = function.getString("name")
                notices[session.id] = ToolPresentation.progress(name); refresh()
                val toolStarted = PerformanceTrace.now()
                var toolStatus = PerformanceTrace.Status.RETURNED
                val result = try {
                    val args = JSONObject(function.getString("arguments"))
                    if (!ToolPresentation.isInternal(name)) activeTools[session.id] = ToolActivity(call.getString("id"), toolLabel(name, args))
                    refresh()
                    withContext(Dispatchers.IO) {
                        when (name) {
                            "send_file" -> {
                                val item = Attachments.inspect(store.workspace, session.id, args.getString("path"), args.optString("caption"))
                                withContext(Dispatchers.Main) {
                                    if (!Attachments.existing(session.messages, item)) {
                                        require(session.messages.drop(session.messages.indexOfLast { it.role == "user" }.coerceAtLeast(0)).count { it.role == "attachment" } < 8) { "本轮最多8个附件" }
                                        session.messages.add(Entry("attachment", item.toString())); persist()
                                    }
                                }
                                JSONObject().put("status", "ok").put("attached", true).put("file", item).toString()
                            }
                            "python_env" -> pythonEnvironment.execute(session.id, args)
                            "environment", "environment_check" -> capabilityTool.execute(session.id, args, trace)
                            "terminal" -> terminals.execute(session.id, args)
                            "android" -> androidTools.execute(args)
                            "profile_search" -> store.profile(args.getString("query"))
                            "list_files" -> store.workspace.list(session.id).joinToString("\n") { it.relativeTo(store.workspace.folder(session.id, "workspace").canonicalFile).path }
                            "read_file" -> store.workspace.file(session.id, args.getString("path")).let { require(it.length() <= 128_000) { "文件超过128KB" }; it.readText() }
                            "write_file" -> { val text = args.getString("content"); require(text.toByteArray().size <= 1_000_000); store.workspace.file(session.id, args.getString("path")).apply { parentFile?.mkdirs(); writeText(text) }; "写入成功" }
                            "python_run" -> shortTasks.execute(session.id, args, trace)
                            else -> "未知工具"
                        }
                    }
                } catch (e: CancellationException) { toolStatus = PerformanceTrace.Status.CANCELLED; throw e }
                catch (e: Exception) { toolStatus = PerformanceTrace.Status.ERROR; "工具失败：${e.message}" }
                finally {
                    trace.event(PerformanceTrace.Stage.TOOL, PerformanceTrace.elapsed(toolStarted), toolStatus, tool = name)
                    activeTools.remove(session.id); refresh()
                }
                if (!ToolPresentation.isInternal(name)) {
                    session.messages.add(Entry("tool", "$name\n${result.take(40_000)}")); persist()
                }
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result.take(40_000)))
                checkpoint()
            }
        }
        session.messages.add(Entry("status", "已达到本轮 $MAX_MODEL_CALLS 次模型请求上限（含网络重试），结果已保留；发送“继续”可接续。"))
        outcome = PerformanceTrace.Status.BUDGET
        } catch (cancel: CancellationException) { outcome = PerformanceTrace.Status.CANCELLED; throw cancel }
        finally { trace.finish(outcome) }
    }
}
