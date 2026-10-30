package ai.capri.app

import ai.capri.app.model.ModelAccount
import ai.capri.app.model.ModelOption
import ai.capri.app.model.ModelProtocol
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WebModelPickerDeviceTest {
    @Test fun selectionSyncsWhileBusyAndSearchPaginatesWithoutCredentials() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: CapriModel
        scenario.onActivity { vm = ViewModelProvider(it)[CapriModel::class.java] }
        val chat = Session(title = "Model picker isolated fixture")
        val other = Session(title = "Model picker other fixture")
        val account = ModelAccount(provider = "custom", name = "Picker fixture ${UUID.randomUUID()}", endpoint = "https://example.com/v1", key = "picker-secret-never-export", protocol = ModelProtocol.CHAT,
            models = (0..54).map { ModelOption("fixture-$it", "Fixture $it", context = 131072) })
        val busy = Job()
        fun call(action: String, payload: JSONObject, id: String = UUID.randomUUID().toString()): JSONObject {
            lateinit var ticket: DebugWebRuntime.Ticket
            ins.runOnMainSync { ticket = DebugWebRuntime.prepare(vm, Bundle().apply { putString("epoch", DebugWebBridge.epoch); putString("chat_id", chat.id); putString("action", action); putString("payload", payload.toString()); putString("request_id", id) }) }
            return ticket.read()
        }
        fun snapshot(): JSONObject {
            lateinit var value: JSONObject
            ins.runOnMainSync { value = DebugWebBridge.call(vm, "web_state", Bundle().apply { putString("chat_id", chat.id) }) }
            return value
        }
        try {
            ins.runOnMainSync {
                vm.sessions.addAll(listOf(chat, other)); vm.store.models.save(account)
                vm.store.models.select(chat.id, account.id, "fixture-0"); vm.store.models.select(other.id, account.id, "fixture-1")
                vm.jobs[chat.id] = busy; vm.refresh()
            }
            val before = snapshot()
            assertTrue(before.getBoolean("busy")); assertEquals("fixture-0", before.getJSONObject("selected_model").getString("id"))
            assertFalse(before.toString().contains(account.key))
            val options = call("snapshot", JSONObject().put("section", "model_options").put("id", account.id))
            assertEquals(50, options.getJSONArray("models").length()); assertTrue(options.getBoolean("has_more"))
            val filtered = call("snapshot", JSONObject().put("section", "model_options").put("id", account.id).put("query", "fixture-54"))
            assertEquals(1, filtered.getJSONArray("models").length()); assertFalse(filtered.getBoolean("has_more"))
            val id = UUID.randomUUID().toString(); val payload = JSONObject().put("account", account.id).put("model", "fixture-54")
            var result = call("model_select", payload, id)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (result.optString("state") == "running" && System.nanoTime() < deadline) { Thread.sleep(25); result = call("operation", JSONObject().put("id", id)) }
            assertEquals(result.toString(), "completed", result.getString("state"))
            assertEquals("completed", call("model_select", payload, id).getString("state"))
            assertEquals("fixture-54", snapshot().getJSONObject("selected_model").getString("id"))
            assertEquals("fixture-1", vm.store.models.selected(other.id)!!.second.id)
            assertTrue(busy.isActive); assertTrue(chat.messages.isEmpty())
        } finally {
            ins.runOnMainSync {
                vm.jobs.remove(chat.id); busy.cancel(); vm.store.models.forgetChat(chat.id); vm.store.models.forgetChat(other.id); vm.store.models.remove(account.id)
                vm.sessions.removeAll { it.id in setOf(chat.id, other.id) }; vm.persist()
            }
            scenario.close()
        }
    }
}
