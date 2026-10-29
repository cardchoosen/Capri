package ai.capri.app

import ai.capri.app.model.ModelApi
import ai.capri.app.model.ModelWire
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in qualitative replay. Calls the configured model, never executes tools or writes chat/memory. */
@RunWith(AndroidJUnit4::class)
class PersonaReplyLiveTest {
    @Test fun replayFinalRepliesWithActualModelAndTools() {
        assumeTrue("Live model evaluation is opt-in", InstrumentationRegistry.getArguments().getString("persona_live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val store = Store(app)
        val personal = store.load().single { it.kind == SessionKind.PERSONAL }
        val config = store.config(personal.id)
        require(config.key.isNotBlank() && config.model.isNotBlank()) { "Configure a model before live evaluation" }
        instrumentation.runOnMainSync {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        var tools: JSONArray? = null
        for (attempt in 0 until 50) {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { !it.isFinishing && !it.isDestroyed }?.let {
                        tools = ViewModelProvider(it)[CapriModel::class.java].builtinTools()
                    }
            }
            if (tools != null) break
            Thread.sleep(100)
        }
        val definitions = requireNotNull(tools)
        val rules = app.assets.open("persona/core.txt").bufferedReader().use { it.readText() } + "\n" +
            app.assets.open("runtime/execution_rules.txt").bufferedReader().use { it.readText() } + "\n" + ExecutionGuidance.text +
            PersonaRuntime.context(SessionKind.PERSONAL, java.time.ZonedDateTime.now().toString(), "")
        val allScenarios = listOf(
            Triple("long_reference_recap", "capri，上次讨论的那个skill扩展方案是什么来着，再给我看看", """{"status":"ok","output":"# 后续扩展（未实现）\n归档了五条方向：同名热点方案、IPv6方向、路由器端镜像、VPN引流、应用内观察。前两条在当时测试条件下未跑通，后三条缺少条件、未实现。这里记录的是当时的讨论和观察，不是对硬件与网络的永久判定。这五条均不在当前skill的已实现能力里，已有脚本功能不变。","complete":true,"has_more":false}"""),
            Triple("cached_environment", "诶，怎么一下就查完了，你这回真动了root吗？", """{"status":"ok","data":{"checks_reused":3,"checks_run":0,"root_invocations":0,"current_command_verified":false}}"""),
            Triple("partial_failure", "先不用继续执行，只根据刚才的结果告诉我做到哪了。", """{"status":"ok","exit_code":0,"output":"Generated capri.png. PIL Image.verify passed. Preview failed: renderer unavailable.","output_truncated":false}"""),
            Triple("correction", "刚才还打包票，现在又不确定了？", """{"status":"ok","finding":"Only the driver name was identified; capability has not been tested."}"""),
            Triple("casual", "下午被我混过去了，工作倒是一点没动。", ""),
            Triple("detailed", "给我讲清楚为什么查询缓存不能证明这次拿到了root，不要只说两句。", ""),
            Triple("exact_format", "只输出两列Markdown表格：项目、结果。两项分别是已复用检查3项、实际Root执行0次，不要表格外的文字。", "")
        )
        val selected = InstrumentationRegistry.getArguments().getString("persona_cases")?.split(',')?.toSet()
        val scenarios = allScenarios.filter { selected == null || it.first in selected }
        require(scenarios.isNotEmpty())
        val report = JSONObject().put("persona_version", PersonaRuntime.VERSION).put("model", config.model)
            .put("note", "Synthetic replay evidence; no real tool execution, chat or memory writes. Style requires human review.")
        val samples = JSONArray(); report.put("samples", samples)
        val output = File(app.cacheDir, "persona-final-replies-live.json")
        output.writeText(report.toString(2))
        val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false).retryOnConnectionFailure(false).build()
        for ((id, input, evidence) in scenarios) {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", rules))
            // Historical report style must not become the new response template.
            messages.put(JSONObject().put("role", "user").put("content", "之前的文件呢？"))
                .put(JSONObject().put("role", "assistant").put("content", "已完成。\n## 关键证据\n文件已验证。\n## 我的判断\n结果符合要求。\n## 下一步\n你希望继续做什么？"))
            if (id == "correction") messages.put(JSONObject().put("role", "assistant").put("content", "这个驱动肯定支持。"))
            messages.put(JSONObject().put("role", "user").put("content", input))
            if (evidence.isNotEmpty()) {
                val tool = when (id) { "long_reference_recap" -> "read_file"; "partial_failure" -> "python_run"; else -> "android" }
                val args = when (id) {
                    "long_reference_recap" -> JSONObject().put("path", "/workspace/skill/references/future-extensions.md")
                    "cached_environment" -> JSONObject("""{"action":"root_inspect","params":{"queries":[{"id":"kernel","type":"uname"}]}}""")
                    "partial_failure" -> JSONObject().put("code", "from PIL import Image\nImage.new('RGB', (16,16), 'red').save('capri.png')\nImage.open('capri.png').verify()\nprint('Generated capri.png. PIL Image.verify passed. Preview failed: renderer unavailable.')")
                    else -> JSONObject("""{"action":"root_inspect","params":{"queries":[{"id":"driver","type":"readlink","value":"/sys/class/net/wlan0/device/driver"}]}}""")
                }
                messages.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(
                    JSONObject().put("id", "fixture-read").put("type", "function").put("function", JSONObject().put("name", tool).put("arguments", args.toString())))))
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", "fixture-read").put("content", evidence))
            }
            // DeepSeek thinking mode requires a reasoning field on replayed assistant tool calls.
            // This is an explicit synthetic fixture, not a recovered private model trace.
            if (config.protocol == "CHAT" && ai.capri.app.retrieval.ProviderCapabilities.deepSeek(config)) {
                for (i in 0 until messages.length()) messages.getJSONObject(i).let { m ->
                    if (m.optString("role") == "assistant") m.put("_capri_scope", ModelWire.scope(config))
                        .put("reasoning_content", "Synthetic replay fixture: use the supplied prior evidence without executing it again.")
                }
            }
            val body = ModelWire.request(config, PersonaRuntime.requestMessages(messages, SessionKind.PERSONAL), definitions, 4096)
            val request = ModelApi.headers(config, Request.Builder().url(ModelWire.url(config)))
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val started = System.nanoTime()
            val response = client.newCall(request).execute().use {
                if (!it.isSuccessful) {
                    report.put("failure", "HTTP ${it.code}: " + it.body?.string().orEmpty().replace(config.key, "[redacted]").take(1500))
                    output.writeText(report.toString(2))
                    error("Live model returned HTTP ${it.code}; see the local replay report")
                }
                ModelWire.response(config, JSONObject(requireNotNull(it.body).string()))
            }
            val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val text = message.optString("content")
            samples.put(JSONObject().put("id", id).put("user", input).put("evidence", evidence)
                .put("reply", text).put("duration_ms", (System.nanoTime() - started) / 1000000)
                .put("unexpected_tool_calls", message.optJSONArray("tool_calls") ?: JSONArray())
                .put("finish_reason", response.getJSONArray("choices").getJSONObject(0).optString("finish_reason"))
                .put("usage", response.optJSONObject("usage") ?: JSONObject()))
            output.writeText(report.toString(2))
            assertTrue("No final text: $id", text.isNotBlank())
            assertEquals("Replay must conclude without executing tools: $id", 0, message.optJSONArray("tool_calls")?.length() ?: 0)
            if (id == "exact_format") assertTrue(text.trim().lineSequence().all { it.trim().startsWith('|') })
        }
    }
}
