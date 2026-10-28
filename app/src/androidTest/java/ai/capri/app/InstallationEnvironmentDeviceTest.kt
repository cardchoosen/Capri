package ai.capri.app

import ai.capri.app.environment.InstallationEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses temporary sessions and a separate archive; never clears existing conversations or changes the network. */
@RunWith(AndroidJUnit4::class)
class InstallationEnvironmentDeviceTest {
    @Test fun relevantContextExcludesOtherSessionsAndInvalidatedEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "archive-context-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val profile = InstallationEnvironment.isolated(context, directory)
            fun fact(key: String, scope: String, summary: String) = profile.archive.upsert(key, "fixtureprobe", scope, summary,
                "观察", JSONObject().put("fixture", true), "test fixture", profile.fingerprint(scope, "boot"), "boot", 600, "test only")
            fact("fixture.mine", "session:mine", "fixtureprobe local-alpha")
            fact("fixture.other", "session:other", "fixtureprobe other-beta")
            fact("fixture.host", "android_host", "fixtureprobe host-gamma")
            fact("fixture.expired", "android_host", "fixtureprobe stale-delta")
            profile.archive.invalidate({ it.key == "fixture.expired" }, "test invalidation")
            val recall = profile.relevant("mine", "fixtureprobe", 1800)
            assertTrue(recall, recall.contains("local-alpha")); assertTrue(recall.contains("host-gamma"))
            assertFalse(recall.contains("other-beta")); assertFalse(recall.contains("stale-delta"))
            assertEquals("", profile.relevant("mine", "fixtureprobe", 0))
        } finally { directory.deleteRecursively() }
    }

    @Test fun cacheSkipsActualProbeAndRefreshFileChangeAndSessionScopeAreRespected() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Store(context).workspace
        val runtime = PythonRuntime(context, workspace)
        val id = "env-fixture-${UUID.randomUUID()}"
        val other = "$id-other"
        val directory = File(context.cacheDir, "archive-${UUID.randomUUID()}").apply { mkdirs() }
        val profile = InstallationEnvironment.isolated(context, directory)
        val tool = CapabilityTool(context, runtime, profile)
        try {
            check(runtime.ready) { "Test requires the installed Ubuntu environment; it never performs legacy migration" }
            runtime.ensureSession(id); runtime.ensureSession(other)
            val home = workspace.folder(id, "home")
            val executable = File(home, ".venv/bin/capri-env-fixture")
            val counter = File(home, "environment-probe-count")
            fun script(version: Int) {
                executable.writeText("#!/bin/sh\nn=0; [ ! -f /root/environment-probe-count ] || n=\$(cat /root/environment-probe-count)\nprintf '%s' \$((n+1)) > /root/environment-probe-count\nprintf 'fixture-version-$version\\n'\n")
                check(executable.setExecutable(true))
            }
            fun request(action: String = "inspect", extra: Boolean = false) = JSONObject().put("action", action)
                .put("requirements", JSONObject().put("commands", org.json.JSONArray(if (extra) listOf("capri-env-fixture", "sh") else listOf("capri-env-fixture"))))
            script(1)
            val first = JSONObject(tool.execute(id, request()))
            assertTrue(first.toString(), first.optBoolean("requirements_satisfied"))
            assertEquals("1", counter.readText())
            val checked = profile.archive.facts().first { it.key == "check.command:capri-env-fixture" }.checked
            val cached = JSONObject(tool.execute(id, request()))
            assertTrue(cached.toString(), cached.getBoolean("cached"))
            assertEquals("1", counter.readText())
            assertEquals(checked, profile.archive.facts().first { it.key == "check.command:capri-env-fixture" }.checked)
            val partial = JSONObject(tool.execute(id, request(extra = true)))
            assertTrue(partial.toString(), partial.getJSONObject("checks").getJSONObject("command:capri-env-fixture").getBoolean("cached"))
            assertEquals("1", counter.readText())
            script(200) // Exact executable metadata changes even though its parent bin directory does not.
            val changed = JSONObject(tool.execute(id, request()))
            assertFalse(changed.optBoolean("cached")); assertEquals("2", counter.readText())
            val fresh = JSONObject(tool.execute(id, request("refresh")))
            assertFalse(fresh.optBoolean("cached")); assertEquals("3", counter.readText())
            val isolated = JSONObject(tool.execute(other, request()))
            assertFalse(isolated.getBoolean("requirements_satisfied"))
            assertFalse(isolated.getJSONObject("checks").getJSONObject("command:capri-env-fixture").getBoolean("present"))
            val restarted = InstallationEnvironment.isolated(context, directory)
            assertTrue(restarted.cachedChecks(id, request().getJSONObject("requirements")).has("command:capri-env-fixture"))
            assertTrue(restarted.archive.document.readText().contains("fixture-version-200"))
        } finally {
            RuntimeFiles.delete(workspace.root(id)); RuntimeFiles.delete(workspace.root(other)); RuntimeFiles.delete(directory)
        }
    }

    @Test fun openEndedHardwareObservationsRequireRealReceiptsAndCannotRewriteContextOrPermissions() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "archive-observation-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val profile = InstallationEnvironment.isolated(context, directory)
            // A local, read-only process supplies evidence; this is a fixture observation, not a device capability claim.
            val process = ProcessBuilder("/system/bin/uname", "-r").start()
            val output = process.inputStream.bufferedReader().use { it.readText() }; assertEquals(0, process.waitFor())
            val receipt = profile.receipt("fixture uname", "android_host", output)
            val args = JSONObject().put("action", "record").put("key", "kernel.runtime.release").put("title", "内核版本观察")
                .put("conclusion", "内核版本：${output.trim()}；不推断任何驱动功能").put("source_receipt", receipt).put("excerpt", output.trim())
            val recorded = JSONObject(profile.record(args))
            assertEquals("观察", recorded.getString("classification")); assertEquals("android_host", recorded.getString("scope"))
            val current = JSONObject(profile.lookup(JSONObject().put("query", "内核")))
            assertTrue(current.getInt("total") > 0)
            assertThrows(IllegalArgumentException::class.java) { profile.record(JSONObject(args.toString()).put("excerpt", "fabricated driver result")) }
            assertThrows(Exception::class.java) { profile.record(JSONObject(args.toString()).put("source_receipt", UUID.randomUUID().toString())) }
            assertTrue(profile.archive.document.readText().contains(output.trim()))
            val calls = org.json.JSONArray().put(JSONObject().put("role", "assistant").put("tool_calls", org.json.JSONArray().put(
                JSONObject().put("id", "lookup-call").put("function", JSONObject().put("name", "environment").put("arguments", "{\"action\":\"lookup\",\"query\":\"内核\"}")))))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "lookup-call").put("content", current.toString()))
            profile.invalidate("android_host", "fixture environment changed")
            profile.refreshResults(calls)
            assertTrue(calls.getJSONObject(1).getString("content").contains("fixture environment changed"))
            profile.clearReceipts()
            assertThrows(Exception::class.java) { profile.record(args) }
        } finally { RuntimeFiles.delete(directory) }
    }
}
