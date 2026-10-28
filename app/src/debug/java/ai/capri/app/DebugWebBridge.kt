package ai.capri.app

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Read directly from the same main-thread model as Compose. No second chat store. */
internal object DebugWebBridge {
    val epoch = UUID.randomUUID().toString()
    private val created = linkedMapOf<String, String>()
    fun call(vm: CapriModel, method: String, args: Bundle): JSONObject { return when (method) {
        "web_state" -> {
            val chat = args.getString("chat_id").orEmpty().ifBlank { vm.selected }
            val session = vm.sessions.firstOrNull { it.id == chat } ?: vm.personalSession()
            val actualChat = session.id
            val revision = vm.updates.value
            if (actualChat == chat && !args.containsKey("before") && args.getString("epoch") == epoch && args.getInt("revision", -1) == revision) {
                return JSONObject().put("epoch", epoch).put("revision", revision).put("chat_id", chat).put("unchanged", true)
            }
            val before = args.getInt("before", session.messages.size).coerceAtMost(session.messages.size)
            require(before >= 0) { "invalid history cursor" }
            val entries = mutableListOf<JSONObject>()
            var index = before
            var bytes = 0
            while (index > 0 && entries.size < 24 && bytes < 48000) {
                index--
                val entry = session.messages[index]
                if (!ToolPresentation.visible(entry)) continue
                val text = entry.text.take(16000)
                bytes += text.toByteArray().size
                entries.add(JSONObject().put("index", index).put("role", entry.role).put("text", text)
                    .put("truncated", text.length < entry.text.length).put("final_reply", entry.finalReply).put("timestamp", entry.timestamp))
            }
            JSONObject().put("epoch", epoch).put("revision", vm.updates.value) .put("selected", vm.selected).put("phone_tab", vm.activeTab)
                .put("chat_id", actualChat).put("title", session.title).put("busy", vm.jobs.containsKey(actualChat))
                .put("deleting", actualChat in vm.deletingChats).put("deletion_error", vm.deletionError)
                .put("notice", vm.notices[actualChat].orEmpty()).put("active_tool", vm.activeTools[actualChat]?.label.orEmpty())
                .put("memory_revision", vm.memoryRevision).put("preparing", vm.migrating).put("total", session.messages.size).put("before", index)
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
