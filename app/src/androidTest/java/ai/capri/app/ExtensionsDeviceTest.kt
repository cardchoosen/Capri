package ai.capri.app

import ai.capri.app.extensions.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ExtensionsDeviceTest {
    private val tool = JSONObject("""{"name":"echo","description":"Return supplied text and current chat state","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}}""")
    @Test fun localRemoteSkillsAndCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Store(context).workspace
        val runtime = PythonRuntime(context, workspace)
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false) }
        var remoteCalls = 0
        var gotAuth = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE") return MockResponse().setResponseCode(200)
                if (request.method == "GET") return MockResponse().setResponseCode(405)
                val body = JSONObject(request.body.readUtf8())
                if (!body.has("id")) return MockResponse().setResponseCode(202)
                gotAuth = request.getHeader("Authorization") == "Bearer fixture-secret-never-return"
                val result = when (body.getString("method")) {
                    "initialize" -> JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject().put("tools", JSONObject())).put("serverInfo", JSONObject().put("name", "Fixture").put("version", "1"))
                    "tools/list" -> JSONObject().put("tools", JSONArray().put(tool))
                    "tools/call" -> { remoteCalls++; JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", body.getJSONObject("params").getJSONObject("arguments").getString("text")))) }
                    else -> JSONObject()
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("jsonrpc", "2.0").put("id", body.get("id")).put("result", result).toString(2))
            }
        }
        server.start()
        val manager = ExtensionManager(context, runtime, workspace) { okhttp3.OkHttpClient.Builder().sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager) }
        val local = "test-local-${UUID.randomUUID().toString().take(8)}"
        val remote = "test-remote-${UUID.randomUUID().toString().take(8)}"
        val skill = "test-skill-${UUID.randomUUID().toString().take(8)}"
        val first = "test-mcp-${UUID.randomUUID()}"; val second = "test-mcp-${UUID.randomUUID()}"
        try {
            // Recover only this test's fixtures after an earlier process crash; never touch real definitions.
            for (c in manager.store.configs().filter { it.getString("id").startsWith("test-local-") || it.getString("id").startsWith("test-remote-") }) { val id = c.getString("id"); manager.store.removeConfig(id); RuntimeFiles.delete(File(manager.store.root, "mcp/$id")) }
            manager.store.skills().filter { it.id.startsWith("test-skill-") }.forEach { manager.store.removeSkill(it.id) }
            File(context.filesDir, "sessions").listFiles().orEmpty().filter { it.name.startsWith("test-mcp-") }.forEach(RuntimeFiles::delete)
            assertEquals(5, manager.store.skills().count { it.builtin })
            val zip = ByteArrayOutputStream()
            ZipOutputStream(zip).use { out ->
                out.putNextEntry(ZipEntry("SKILL.md")); out.write("---\nname: $skill\ndescription: Test skill\n---\nRead references/example.txt then use the existing tools.".toByteArray()); out.closeEntry()
                out.putNextEntry(ZipEntry("references/example.txt")); out.write("fixture-reference".toByteArray()); out.closeEntry()
            }
            assertEquals(skill, manager.store.importSkill(zip.toByteArray().inputStream(), true))
            val loaded = JSONObject(manager.execute(first, JSONObject().put("action", "load_skill").put("id", skill)))
            assertTrue(loaded.getString("instructions").contains("Test skill"))
            assertEquals("fixture-reference", workspace.file(first, ".capri/skills/$skill/references/example.txt").readText())
            assertEquals("fixture-reference", JSONObject(manager.execute(first, JSONObject().put("action", "read_resource").put("id", skill).put("path", "references/example.txt"))).getString("content"))
            assertTrue(runCatching { manager.execute(first, JSONObject().put("action", "read_resource").put("id", skill).put("path", "../../persona/core.txt")) }.isFailure)
            assertTrue(runCatching { manager.store.importSkill(zip.toByteArray().inputStream(), true) }.isFailure)
            val unsafeZip = ByteArrayOutputStream()
            ZipOutputStream(unsafeZip).use { it.putNextEntry(ZipEntry("../escape")); it.write("bad".toByteArray()); it.closeEntry() }
            assertTrue(runCatching { manager.store.importSkill(unsafeZip.toByteArray().inputStream(), true) }.isFailure)
            val fixture = """
import json,os,sys,time
from pathlib import Path
for line in sys.stdin:
    r=json.loads(line)
    if 'id' not in r: continue
    m=r['method']
    if m=='initialize': result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'Local fixture','version':'1'}}
    elif m=='tools/list': result={'tools':[json.loads('${tool.toString()}')]}
    elif m=='tools/call':
        text=r['params']['arguments']['text']
        if text=='timeout': time.sleep(7)
        if text=='wait':
            Path('/workspace/started').touch()
            time.sleep(3)
            Path('/workspace/late').touch()
        state=Path.home()/'state'
        previous=state.read_text() if state.exists() else ''
        state.write_text(text)
        result={'content':[{'type':'text','text':json.dumps({'text':text,'previous':previous,'cwd':os.getcwd(),'git_broker':os.getenv('CAPRI_GIT_SOCKET'),'ssh_home':(Path.home()/'.ssh').exists()})}]}
    else: result={}
    print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':result}),flush=True)
""".trimIndent()
            for (chat in listOf(first, second)) { runtime.ensureSession(chat); workspace.file(chat, "mcp_fixture.py").writeText(fixture) }
            manager.store.saveConfig(JSONObject().put("id", local).put("name", "Local fixture").put("timeout_seconds", 5).put("type", "local").put("enabled", true).put("command", JSONArray(listOf("/usr/bin/python3", "-u", "/workspace/mcp_fixture.py"))))
            suspend fun echo(chat: String, text: String): JSONObject {
                manager.activate(chat, local)
                val name = ExtensionManager.toolName(local, "echo")
                val reply = JSONObject(manager.call(chat, name, JSONObject().put("text", text)))
                return JSONObject(reply.getJSONArray("content").getJSONObject(0).getString("text"))
            }
            val a = echo(first, "first"); assertEquals("", a.getString("previous")); assertEquals("/workspace", a.getString("cwd")); assertTrue(a.isNull("git_broker")); assertFalse(a.getBoolean("ssh_home"))
            assertEquals("", echo(second, "second").getString("previous"))
            assertEquals("first", echo(first, "first-again").getString("previous"))
            val localSchema = manager.specifications(first).single().getJSONObject("function").getJSONObject("parameters")
            assertFalse(localSchema.toString(), localSchema.getBoolean("additionalProperties"))
            manager.store.saveConfig(JSONObject().put("id", remote).put("name", "HTTPS fixture").put("type", "remote").put("enabled", true).put("url", server.url("/mcp").toString()))
            manager.store.saveSecret(remote, "fixture-secret-never-return")
            manager.activate(first, remote)
            val response = manager.call(first, ExtensionManager.toolName(remote, "echo"), JSONObject().put("text", "remote-ok"))
            assertTrue(response, response.contains("remote-ok")); assertTrue(gotAuth); assertEquals(1, remoteCalls)
            assertFalse(manager.specifications(first).first { it.getJSONObject("function").getString("name") == ExtensionManager.toolName(remote, "echo") }.getJSONObject("function").getJSONObject("parameters").getBoolean("additionalProperties"))
            assertFalse(manager.execute(first, JSONObject().put("action", "list")).contains("fixture-secret-never-return"))
            manager.deactivate(first, remote)
            assertFalse(manager.specifications(first).any { it.getJSONObject("function").getString("name") == ExtensionManager.toolName(remote, "echo") })
            manager.activate(first, remote)
            assertTrue(manager.call(first, ExtensionManager.toolName(remote, "echo"), JSONObject().put("text", "reconnected")).contains("reconnected"))
            assertEquals(2, remoteCalls)
            manager.deactivate(first, remote)
            val timeout = JSONObject(manager.call(first, ExtensionManager.toolName(local, "echo"), JSONObject().put("text", "timeout")))
            assertEquals(timeout.toString(), "timeout", timeout.getString("reason"))
            assertEquals("unknown", timeout.getString("outcome"))
            assertTrue(manager.specifications(first).isEmpty())
            manager.activate(first, local)
            val restarted = ExtensionManager(context, runtime, workspace)
            assertTrue(restarted.store.hasSecret(remote)); assertEquals("fixture-secret-never-return", restarted.store.secret(remote)); restarted.dispose()
            val job = launch(Dispatchers.IO) { manager.call(first, ExtensionManager.toolName(local, "echo"), JSONObject().put("text", "wait")) }
            withTimeout(10_000) { while (!workspace.file(first, "started").exists()) delay(50) }
            job.cancelAndJoin()
            assertTrue(RuntimeGate.active(context).none { it.chat == first })
            delay(3200)
            assertFalse(workspace.file(first, "late").exists())
            assertTrue(runCatching { manager.call(first, ExtensionManager.toolName(local, "echo"), JSONObject().put("text", "should-not-replay")) }.isFailure)
            manager.deactivate(second, local)
        } finally {
            manager.dispose(); server.shutdown()
            for (id in listOf(local, remote)) { manager.store.removeConfig(id); RuntimeFiles.delete(File(manager.store.root, "mcp/$id")); for (chat in listOf(first, second)) manager.store.setActive(chat, id, false) }
            runCatching { manager.store.removeSkill(skill) }
            for (chat in listOf(first, second)) RuntimeFiles.delete(workspace.root(chat))
        }
    }
}
