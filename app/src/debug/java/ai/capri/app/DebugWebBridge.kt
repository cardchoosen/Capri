package ai.capri.app

import android.os.Bundle
import ai.capri.app.model.DeepSeekReasoning
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Read directly from the same main-thread model as Compose. No second chat store. */
internal object DebugWebBridge {
    val epoch = UUID.randomUUID().toString()
    private val created = linkedMapOf<String, String>()
    private data class HistoryReceipt(val fingerprint: String, val result: JSONObject)
    private val historyRequests = linkedMapOf<String, HistoryReceipt>()
    private fun historyRequest(vm: CapriModel, method: String, args: Bundle): JSONObject {
        require(args.getString("epoch") == epoch) { "App restarted; refresh history." }
        val id = args.getString("request_id").orEmpty()
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "request_id must be a UUID." }
        val chat = args.getString("chat_id").orEmpty()
        val source = args.getString("from_context").orEmpty(); val revision = args.getLong("history_revision", -1)
        val mode = args.getString("mode").orEmpty()
        val payload = JSONObject().put("method",method).put("chat",chat).put("source",source).put("revision",revision)
            .put("entry",args.getString("entry_id")).put("target",args.getString("context_id")).put("mode",mode).put("text",args.getString("text"))
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        historyRequests[id]?.let { require(it.fingerprint == hash) { "request_id conflict." }; return JSONObject(it.result.toString()) }
        check(historyRequests.size < 1000) { "History operation limit; restart Capri." }
        require(method=="web_revise"&&mode in setOf("retry","edit")) { "Unknown history action." }
        val version=vm.reviseMessage(chat,args.getString("entry_id").orEmpty(),if(mode=="edit")args.getString("text")?:error("text required")else null,source,revision)
        return JSONObject().put("chat_id",chat).put("context_id",version).put("accepted",true).also{historyRequests[id]=HistoryReceipt(hash,it)}
    }

    fun call(vm: CapriModel, method: String, args: Bundle): JSONObject { return when (method) {
        "web_rules" -> {
            require(args.getString("epoch")==epoch) { "App restarted; refresh rules." }
            val session=vm.sessions.firstOrNull{it.id==args.getString("chat_id")}?:error("Unknown chat.")
            require(session.contextVersion==args.getString("context_id") && session.id !in vm.deletingChats && !vm.resettingMemory && !vm.memoryResetRequired) { "Conversation changed or is unavailable; refresh rules." }
            val payload=JSONObject(args.getString("payload")?:"{}");val action=payload.getString("action");val scope=payload.optString("scope","chat")
            if(action=="list")vm.userRules.read(scope,session.id).put("status","ok") else {
                val id=args.getString("request_id")?:error("Missing rule request ID.");require(runCatching{java.util.UUID.fromString(id).toString()==id}.getOrDefault(false)) { "Rule request ID must be a UUID." }
                require(action in setOf("save","delete")) { "Unknown rule action." }
                if(action=="save")vm.userRules.save(scope,session.id,payload.getString("id"),payload.getString("title"),payload.getString("content"),payload.getBoolean("enabled"),payload.getLong("expected_revision"))
                else vm.userRules.delete(scope,session.id,payload.getString("id"),payload.getLong("expected_revision"))
            }
        }
        "web_events" -> {
            val session=vm.sessions.firstOrNull { it.id==args.getString("chat_id") }?:error("Unknown chat.")
            require(session.contextVersion==args.getString("context_id")) { "History changed; refresh events." }
            val scope=HistoryEditing.actualContextKey(session)
            vm.taskEvents.read(scope,args.getLong("after",0)).put("jobs",vm.durableJobs.list(scope))
        }
        "web_revise" -> historyRequest(vm,method,args)
        "web_message" -> {
            require(args.getString("epoch") == epoch) { "App restarted; refresh history." }
            val session = vm.sessions.firstOrNull { it.id == args.getString("chat_id") } ?: error("Unknown chat.")
            require(session.id !in vm.deletingChats && session.contextVersion == args.getString("from_context") && session.historyVersion == args.getLong("history_revision",-1)) { "History changed; refresh." }
            val message = session.messages.firstOrNull { it.id == args.getString("entry_id") && it.role == "user" } ?: error("User message not found.")
            JSONObject().put("entry_id",message.id).put("text",message.text.take(128000)).put("truncated",message.text.length > 128000)
        }
        "web_state" -> {
            val chat = args.getString("chat_id").orEmpty().ifBlank { vm.selected }
            val session = vm.sessions.firstOrNull { it.id == chat } ?: vm.personalSession()
            val actualChat = session.id
            if (args.containsKey("before") && args.containsKey("context_id")) require(args.getString("context_id") == session.contextVersion) { "History changed; reload." }
            val revision = vm.updates.value
            if (actualChat == chat && !args.containsKey("before") && args.getString("epoch") == epoch && args.getInt("revision", -1) == revision) {
                return JSONObject().put("epoch", epoch).put("revision", revision).put("chat_id", chat).put("unchanged", true)
            }
            val before = args.getInt("before", session.messages.size).coerceAtMost(session.messages.size)
            require(before >= 0) { "invalid history cursor" }
            val visible = session.messages.filter(ToolPresentation::visible)
            val process = mutableMapOf<String, JSONObject>()
            timelineRows(visible, vm.jobs.containsKey(actualChat), vm.activeTools[actualChat]).filterIsInstance<TimelineRow.History>().forEach { row ->
                val key = row.entries.firstOrNull()?.id ?: "active"
                row.entries.forEach { entry -> process[entry.id] = JSONObject().put("process_group", key).put("process_count", row.toolCount)
                    .put("process_running", row.running).put("process_label", row.activity?.label ?: "") }
            }
            val entries = mutableListOf<JSONObject>()
            var index = before
            var bytes = 0
            while (index > 0 && entries.size < 24 && bytes < 48000) {
                index--
                val entry = session.messages[index]
                if (!ToolPresentation.visible(entry)) continue
                val text = entry.text.take(16000)
                bytes += text.toByteArray().size
                entries.add(JSONObject().put("index", index).put("id", entry.id).put("role", entry.role).put("text", text)
                    .put("truncated", text.length < entry.text.length).put("final_reply", entry.finalReply).put("timestamp", entry.timestamp).apply {
                        process[entry.id]?.let { meta -> meta.keys().forEach { key -> put(key, meta.get(key)) } }
                        if (entry.role == "tool") put("tool_label", toolRecordLabel(entry))
                    })
            }
            JSONObject().put("epoch", epoch).put("revision", vm.updates.value) .put("selected", vm.selected).put("phone_tab", vm.activeTab)
                .put("chat_id", actualChat).put("title", session.title).put("busy", vm.jobs.containsKey(actualChat))
                .put("deleting", actualChat in vm.deletingChats).put("deletion_error", vm.deletionError)
                .put("notice", vm.notices[actualChat].orEmpty()).put("active_tool", vm.activeTools[actualChat]?.label.orEmpty())
                .put("context_id",session.contextVersion).put("history_revision",session.historyVersion)
                .put("run_state", vm.turnView(actualChat))
                .put("selected_model", vm.store.models.selected(actualChat)?.let { (account, model) ->
                    val config = vm.store.config(actualChat)
                    model.json().put("account", account.id).put("account_name", account.name).put("provider", account.provider)
                        .put("reasoning_options", JSONArray(DeepSeekReasoning.options(config)))
                        .put("reasoning_effort", config.reasoningEffort ?: "default")
                } ?: JSONObject.NULL)
                .put("input_drafts", JSONArray(vm.pendingInputs(actualChat))).put("memory_revision", vm.memoryRevision).put("preparing", vm.migrating).put("total", session.messages.size).put("before", index)
                .put("entries", JSONArray(entries.reversed())).put("sessions", JSONArray(vm.sessions.sortedBy { if (it.kind == SessionKind.PERSONAL) 0 else 1 }.map {
                    JSONObject().put("chat_id", it.id).put("title", it.title).put("kind", it.kind.name)
                        .put("busy", vm.jobs.containsKey(it.id)).put("deleting", it.id in vm.deletingChats)
                }))
        }
        "web_select" -> {
            require(args.getString("epoch") == epoch) { "app restarted; refresh before selecting a chat" }
            val chat = args.getString("chat_id").orEmpty()
            require(vm.sessions.any { it.id == chat } && chat !in vm.deletingChats) { "unknown or deleting chat" }
            if (vm.activeTab == 0) vm.select(chat)
            JSONObject().put("selected", vm.selected).put("phone_synced", vm.activeTab == 0)
        }
        "web_create" -> {
            require(args.getString("epoch") == epoch) { "app restarted; refresh before creating a chat" }
            val id = args.getString("request_id").orEmpty()
            require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "request_id must be a UUID" }
            val previous = created[id]
            if (previous != null) JSONObject().put("chat_id", previous)
            else {
                check(!vm.migrating && !vm.runtimeBusy && !vm.resettingMemory && !vm.memoryResetRequired) { "environment busy" }
                check(created.size < 1000) { "web create limit; restart Capri" }
                val chat = Session()
                vm.store.models.initializeChat(chat.id)
                vm.sessions.add(0, chat); created[id] = chat.id; vm.persist(); vm.prepareSession(chat.id)
                if (vm.activeTab == 0) vm.select(chat.id)
                JSONObject().put("chat_id", chat.id)
            }
        }
        "web_delete" -> {
            require(args.getString("epoch") == epoch) { "app restarted; refresh before deleting a chat" }
            val chat = args.getString("chat_id").orEmpty()
            require(chat.isNotBlank()) { "chat_id required" }
            vm.deleteSession(chat)
            JSONObject().put("chat_id", chat).put("accepted", true)
        }
        "web_stop" -> {
            require(args.getString("epoch") == epoch) { "app restarted; refresh before stopping a task" }
            val chat = args.getString("chat_id").orEmpty()
            require(vm.sessions.any { it.id == chat }) { "unknown chat_id" }
            vm.jobs[chat]?.cancel(); vm.refresh()
            JSONObject().put("chat_id", chat).put("stop_requested", true)
        }
        else -> error("unknown web method")
    }
    }
}
