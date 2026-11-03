package ai.capri.app

import ai.capri.app.extensions.ExtensionManager
import ai.capri.app.rea.ReaTargets
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ReaTargetsDeviceTest {
    @Test fun workspaceSnapshotPreservesInputsRejectsEscapesAndCleansFailures() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation(); val context = ins.targetContext
        val workspace = Workspace(File(context.filesDir, "sessions")); val chat = "rea-target-${UUID.randomUUID()}"
        val targets = ReaTargets(context, workspace)
        try {
            val source = workspace.file(chat, "fixture.apk")
            ins.context.assets.open("rea-fixture.apk").use { input -> source.outputStream().use(input::copyTo) }
            val digest = RuntimeFiles.sha(source)
            val result = targets.acquire(chat, JSONObject().put("path", "/workspace/fixture.apk"))
            val file = result.getJSONArray("files").getJSONObject(0)
            assertEquals(digest, RuntimeFiles.sha(source))
            assertEquals(digest, file.getString("sha256"))
            assertEquals(digest, RuntimeFiles.sha(workspace.file(chat, file.getString("path").removePrefix("/workspace/"))))
            assertFalse(file.has("inventory"))
            val manifest = JSONObject(workspace.file(chat, result.getString("manifest_path").removePrefix("/workspace/")).readText())
            assertTrue(manifest.getJSONArray("files").getJSONObject(0).getJSONArray("inventory").toString().contains("AndroidManifest.xml"))
            for (bad in listOf("../escape.apk", "/etc/passwd")) assertTrue(runCatching { targets.acquire(chat, JSONObject().put("path", bad)) }.isFailure)
            val snapshots = workspace.file(chat, ".capri/rea/targets").listFiles()!!.size
            workspace.file(chat, "broken.apk").writeText("not a ZIP")
            assertTrue(runCatching { targets.acquire(chat, JSONObject().put("path", "broken.apk")) }.isFailure)
            assertEquals(snapshots, workspace.file(chat, ".capri/rea/targets").listFiles()!!.size)
            assertTrue(RuntimeGate.active(context).none { it.chat == chat })
        } finally { SessionDeletion(context.filesDir).cleanup(chat) }
    }

    @Test fun installedSelfSnapshotFeedsActualReaSignatureInspection() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation(); val context = ins.targetContext
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val workspace = Workspace(File(context.filesDir, "sessions"))
        val manager = ExtensionManager(context, PythonRuntime(context, workspace), workspace)
        val chat = "rea-signature-${UUID.randomUUID()}"; val previous = manager.rea.enabled
        try {
            manager.setReaEnabled(chat, false)
            val request = JSONObject().put("action", "rea_target").put("package_name", context.packageName)
            assertTrue(runCatching { manager.execute(chat, request) }.isFailure)
            manager.setReaEnabled(chat, true)
            val snapshot = JSONObject(manager.execute(chat, request))
            val files = snapshot.getJSONArray("files")
            assertEquals(context.packageName, snapshot.getString("package_name"))
            assertTrue(files.length() >= 1)
            val base = files.getJSONObject(0)
            assertEquals(RuntimeFiles.sha(File(context.applicationInfo.sourceDir)), base.getString("sha256"))
            val identity = snapshot.getJSONObject("installed_identity").getJSONArray("current_signer_sha256")
            assertTrue(identity.length() >= 1)
            manager.activate(chat, "capri-rea-apk")
            val name = ExtensionManager.toolName("capri-rea-apk", "inspect_apk_signature")
            assertTrue(manager.specifications(chat).any { it.getJSONObject("function").getString("name") == name })
            val reply = manager.call(chat, name, JSONObject().put("path", base.getString("path")))
            File(context.cacheDir, "rea-signature-result.json").writeText(reply)
            assertFalse(reply.take(2400), JSONObject(reply).optBoolean("isError"))
            // Compare exact DER fingerprint with Android's installed identity, not display names.
            assertTrue(reply.take(2400), reply.lowercase().contains(identity.getString(0).lowercase()))
            assertTrue(reply.take(2400), reply.contains("unverified"))
        } finally {
            manager.deactivate(chat, "capri-rea-apk"); manager.setReaEnabled(chat, previous)
            manager.deleteChat(chat); SessionDeletion(context.filesDir).cleanup(chat); activity.close()
        }
    }
}
