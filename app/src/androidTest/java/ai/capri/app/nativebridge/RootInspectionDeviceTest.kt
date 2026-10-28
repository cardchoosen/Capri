package ai.capri.app.nativebridge

import ai.capri.app.environment.InstallationEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RootInspectionDeviceTest {
    @Test fun batchesRootChecksReusesPersistedEvidenceAndOnlyRefreshesMissingItems() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "root-inspection-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val profile = InstallationEnvironment.isolated(context, directory)
            val root = RootRuntime(context)
            val inspection = RootInspection(root, profile)
            val base = JSONObject("""{"queries":[{"id":"model","type":"getprop","value":"ro.product.model"},{"id":"kernel","type":"uname"},{"id":"shell","type":"command_exists","value":"sh"}]}""")
            val first = inspection.execute(base, directory)
            assertEquals(first.toString(), "ok", first.getString("status"))
            val fresh = first.getJSONObject("data")
            assertEquals(1, fresh.getInt("root_invocations")); assertEquals(3, fresh.getInt("checks_run"))
            assertTrue(fresh.getBoolean("current_command_verified")); assertEquals(0, fresh.getInt("effective_uid"))
            assertTrue(fresh.getBoolean("cleanup_verified"))
            val checked = profile.archive.facts().associate { it.key to it.checked }
            // The next model round reads the archive; that must not invalidate the batch cache.
            profile.overview(); profile.lookup(JSONObject().put("query", "uname"))
            val repeated = RootInspection(root, InstallationEnvironment.isolated(context, directory)).execute(base, directory).getJSONObject("data")
            assertEquals(repeated.toString(), 0, repeated.getInt("root_invocations"))
            assertEquals(3, repeated.getInt("checks_reused")); assertFalse(repeated.getBoolean("current_command_verified"))
            assertEquals(checked, profile.archive.facts().associate { it.key to it.checked })
            val extra = JSONObject(base.toString())
            extra.getJSONArray("queries").put(JSONObject("""{"id":"absent","type":"command_exists","value":"capri_nonexistent_fixture_tool"}"""))
            val mixed = inspection.execute(extra, directory)
            assertEquals(mixed.toString(), "partial", mixed.getString("status"))
            assertEquals(1, mixed.getJSONObject("data").getInt("checks_run"))
            assertEquals(3, mixed.getJSONObject("data").getInt("checks_reused"))
            assertEquals("failed", mixed.getJSONObject("data").getJSONArray("results").getJSONObject(3).getString("status"))
            val refresh = inspection.execute(JSONObject(base.toString()).put("refresh", true), directory).getJSONObject("data")
            assertEquals(1, refresh.getInt("root_invocations")); assertEquals(3, refresh.getInt("checks_run"))
            val negative = inspection.execute(extra, directory).getJSONObject("data")
            assertEquals(0, negative.getInt("root_invocations")); assertEquals(4, negative.getInt("checks_reused"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun metadataChangesInvalidateCacheAndMalformedCallsFailBeforeRoot() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "root-watch-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val profile = InstallationEnvironment.isolated(context, directory)
            val inspection = RootInspection(RootRuntime(context), profile)
            val executable = File(directory, "fixture").apply { writeText("#!/system/bin/sh\nprintf SHOULD_NEVER_RUN > ${directory.path}/executed\n"); setExecutable(true) }
            val request = JSONObject().put("queries", JSONArray().put(JSONObject().put("id", "file").put("type", "command_exists").put("value", executable.path)))
            val first = inspection.execute(request, directory)
            assertEquals(first.toString(), "ok", first.getString("status"))
            assertEquals(0, inspection.execute(request, directory).getJSONObject("data").getInt("checks_run"))
            assertTrue(executable.delete()); profile.overview()
            val changed = inspection.execute(request, directory)
            assertEquals(changed.toString(), "partial", changed.getString("status"))
            assertEquals(1, changed.getJSONObject("data").getInt("checks_run"))
            assertFalse(File(directory, "executed").exists())
            try { inspection.execute(JSONObject("""{"queries":[{"id":"x","type":"shell","value":"id"}]}"""), directory); fail("invalid type accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { directory.deleteRecursively() }
    }
}
