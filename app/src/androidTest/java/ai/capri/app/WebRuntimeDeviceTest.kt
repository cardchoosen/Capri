package ai.capri.app

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WebRuntimeDeviceTest {
    @Test fun managementReusesPhoneStateWithoutReturningCredentialsAndDeduplicatesWrites() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: CapriModel
        scenario.onActivity { vm = ViewModelProvider(it)[CapriModel::class.java] }
        val chat = Session(title = "Web Run isolated fixture")
        val modelName = "Run fixture ${UUID.randomUUID()}"
        val skillName = "web-run-${UUID.randomUUID()}"
        var terminal = ""
        val mcp = "run-fixture-${UUID.randomUUID()}"
        fun call(action: String, p: JSONObject = JSONObject(), id: String = UUID.randomUUID().toString(), target: String = chat.id, epoch: String = DebugWebBridge.epoch): JSONObject {
            var ticket: DebugWebRuntime.Ticket? = null
            ins.runOnMainSync { ticket = DebugWebRuntime.prepare(vm, Bundle().apply { putString("epoch", epoch); putString("chat_id", target); putString("action", action); putString("payload", p.toString()); putString("request_id", id) }) }
            return ticket!!.read()
        }
        fun action(name: String, p: JSONObject = JSONObject(), id: String = UUID.randomUUID().toString()): JSONObject {
            var result = call(name,p,id)
            repeat(400) { if (result.optString("state") == "running") { Thread.sleep(25); result = call("operation",JSONObject().put("id",id)) } }
            assertNotEquals(result.toString(), "running", result.optString("state")); return result
        }
        try {
            ins.runOnMainSync { vm.sessions.add(chat); vm.persist() }
            for (section in listOf("overview","models","native","terminal","git","extensions","knowledge","enhancements","archive","browser")) {
                val snapshot = call("snapshot",JSONObject().put("section",section))
                assertFalse(snapshot.toString().contains("fixture-key-never-export"))
            }
            val request = UUID.randomUUID().toString()
            val p = JSONObject().put("provider","custom").put("name",modelName).put("endpoint","https://example.com/v1").put("protocol","CHAT").put("key","fixture-key-never-export").put("manual","fixture-model")
            assertEquals("completed",action("model_save",p,request).getString("state"))
            assertEquals("completed",action("model_save",p,request).getString("state"))
            val models = call("snapshot",JSONObject().put("section","models"))
            assertFalse(models.toString().contains("fixture-key-never-export"))
            val a = vm.store.models.all().single { it.name == modelName }
            assertEquals(1,a.models.size)
            assertEquals("completed",action("model_select",JSONObject().put("account",a.id).put("model","fixture-model")).getString("state"))
            assertEquals(a.id,vm.store.models.selected(chat.id)!!.first.id)
            assertEquals("failed",action("model_remove",JSONObject().put("id",a.id)).getString("state"))
            assertTrue(vm.store.models.all().any { it.id == a.id })
            vm.extensions.store.installConfig(JSONObject().put("id",mcp).put("name","Run fixture").put("type","remote").put("url","https://example.com/mcp").put("enabled",false))
            vm.extensions.store.saveSecret(mcp,"fixture-token-never-export")
            assertFalse(call("snapshot",JSONObject().put("section","extensions")).toString().contains("fixture-token-never-export"))
            assertEquals("completed",action("mcp_toggle",JSONObject().put("id",mcp).put("enabled",true)).getString("state"))
            assertTrue(vm.extensions.store.config(mcp).getBoolean("enabled"))
            assertFalse(vm.extensions.store.active(chat.id,mcp))
            val save = action("memory_save",JSONObject().put("text","Run fixture memory").put("global",false))
            assertEquals(save.toString(),"completed",save.getString("state"))
            val memory = vm.knowledge.records(chat.id).single { it.optString("text") == "Run fixture memory" }
            assertEquals("completed",action("record_change",JSONObject().put("id",memory.getString("id")).put("enabled",false)).getString("state"))
            assertFalse(vm.knowledge.records(chat.id).single { it.getString("id") == memory.getString("id") }.getBoolean("enabled"))
            val up = action("upload_begin",JSONObject().put("kind","ssh").put("size",6)).getJSONObject("data").getString("result")
            var denied = false
            try { call("upload_chunk",JSONObject().put("id",up).put("offset",1).put("base64","YWJj")) } catch (_: Exception) { denied=true }
            assertTrue(denied)
            assertEquals(3,call("upload_chunk",JSONObject().put("id",up).put("offset",0).put("base64","YWJj")).getInt("result"))
            call("upload_abort",JSONObject().put("id",up))
            val skillText = "---\nname: $skillName\ndescription: Verify chunked web import with an isolated English fixture.\n---\n\nRead-only test instructions.\n" + "Evidence only.\n".repeat(4000)
            val bytes = skillText.toByteArray()
            val skillUpload = action("upload_begin",JSONObject().put("kind","skill").put("size",bytes.size)).getJSONObject("data").getString("result")
            for (offset in bytes.indices step 24000) call("upload_chunk",JSONObject().put("id",skillUpload).put("offset",offset).put("base64",android.util.Base64.encodeToString(bytes.copyOfRange(offset,minOf(offset+24000,bytes.size)),android.util.Base64.NO_WRAP)))
            val imported = action("skill_import",JSONObject().put("upload_id",skillUpload).put("zip",false).put("confirm",true))
            assertEquals(imported.toString(),"completed",imported.getString("state"))
            assertTrue(vm.extensions.store.skills().any { it.id == skillName })
            assertEquals("failed",action("root_request").getString("state"))
            assertEquals("failed",action("maintenance",JSONObject().put("id","TEMP")).getString("state"))
            val screenshot = java.io.File(vm.store.workspace.folder(chat.id,"workspace"),"browser/${UUID.randomUUID()}.png")
            screenshot.parentFile!!.mkdirs()
            val png = android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
            screenshot.outputStream().use { png.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; png.recycle()
            lateinit var preview: JSONObject
            ins.runOnMainSync { preview = DebugWebRuntime.rememberPreview(vm,chat.id,JSONObject().put("path","browser/${screenshot.name}")) }
            val previewId = preview.getString("preview_id")
            val image = call("preview_chunk",JSONObject().put("id",previewId).put("offset",0))
            assertArrayEquals(screenshot.readBytes(),android.util.Base64.decode(image.getString("base64"),android.util.Base64.DEFAULT))
            var crossChat = false
            try { call("preview_chunk",JSONObject().put("id",previewId),target=vm.personalSession().id) } catch (_:Exception) { crossChat=true }
            assertTrue(crossChat)
            screenshot.appendText("changed")
            var changed = false
            try { call("preview_chunk",JSONObject().put("id",previewId)) } catch (_:Exception) { changed=true }
            assertTrue(changed)
            val opened = action("terminal",JSONObject().put("args",JSONObject().put("action","open")))
            assertEquals(opened.toString(),"completed",opened.getString("state"))
            val openedData = opened.getJSONObject("data")
            assertEquals(openedData.toString(),"ok",openedData.getString("status"))
            terminal = openedData.getJSONObject("data").getString("terminal_id")
            val command = action("terminal",JSONObject().put("args",JSONObject().put("action","run").put("terminal_id",terminal).put("command","printf 'capri-web-runtime-ok\\n'").put("wait_ms",1000)))
            assertTrue(command.toString(),command.toString().contains("capri-web-runtime-ok"))
            val closed = action("terminal",JSONObject().put("args",JSONObject().put("action","close").put("terminal_id",terminal)))
            assertEquals("ok",closed.getJSONObject("data").getString("status"))
            var stale = false
            ins.runOnMainSync { try { DebugWebRuntime.prepare(vm,Bundle().apply { putString("epoch","stale"); putString("chat_id",chat.id) }) } catch (_: Exception) { stale=true } }
            assertTrue(stale)
        } finally {
            if (terminal.isNotBlank()) { runCatching { action("terminal",JSONObject().put("args",JSONObject().put("action","close").put("terminal_id",terminal))) }; runCatching { action("terminal",JSONObject().put("args",JSONObject().put("action","delete").put("terminal_id",terminal))) } }
            vm.extensions.store.skills().firstOrNull { it.id == skillName }?.let { vm.extensions.store.removeSkill(it.id) }
            vm.store.models.all().filter { it.name == modelName }.forEach { vm.store.models.remove(it.id) }
            vm.store.models.forgetChat(chat.id)
            vm.extensions.store.removeConfig(mcp)
            vm.knowledge.records(chat.id).filter { it.optString("text") == "Run fixture memory" }.forEach { vm.knowledge.change(chat.id,it.getString("id")) }
            ins.runOnMainSync { vm.sessions.removeAll { it.id == chat.id }; vm.persist() }
            RuntimeFiles.delete(vm.store.workspace.root(chat.id))
            scenario.close()
        }
    }
}
