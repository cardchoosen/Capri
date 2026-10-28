package ai.capri.app

import ai.capri.app.environment.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnvironmentArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private var clock = 100000L
    private var writes = 0
    private fun store() = EnvironmentArchive(temp.root, { it.readText() }, { f, t -> f.parentFile!!.mkdirs(); f.writeText(t); writes++ }, { clock })
    private fun save(store: EnvironmentArchive, scope: String = "android_host_root", text: String = "驱动返回capability-X", stamp: String = "stamp1") =
        store.upsert("hardware.wifi.driver", "无线驱动观察", scope, text, "观察", JSONObject().put("driver", "fixture"), "真实工具引用", stamp, "boot", 60)

    @Test fun markdownIsCanonicalAndGenericFactsSurviveReloadWithoutDuplicateTruthStore() {
        val first = store(); val f = save(first)
        assertEquals("观察", f.status)
        val markdown = File(temp.root, "environment.md").readText()
        assertTrue(markdown.contains("无线驱动观察")); assertTrue(markdown.contains("android_host_root"))
        assertFalse(File(temp.root, "environment.json").exists())
        assertEquals(f, store().facts().single())
        assertEquals("fixture", store().evidence(f).getJSONObject("value").getString("driver"))
        save(first, scope = "session:another")
        assertEquals(2, store().facts().size)
        assertEquals(1, store().view(scope = "session:another").getInt("entries"))
    }
    @Test fun unchangedFactsDoNotRewriteDocumentEvidenceOrVerificationTime() {
        val store = store(); val original = save(store); val count = writes
        clock += 2000
        val same = save(store)
        assertEquals(original.checked, same.checked); assertEquals(original.evidence, same.evidence)
        assertEquals(count, writes)
        save(store, text = "发现新的驱动条件")
        assertEquals(1, store.facts().size)
        assertTrue(File(temp.root, "history").listFiles()!!.isNotEmpty())
        assertTrue(store.facts().single().checked > original.checked)
    }
    @Test fun freshnessUsesContextTimeAndExplicitInvalidationInsteadOfPermanentFailure() {
        val store = store(); val f = save(store)
        assertEquals("有效", store.freshness(f, "stamp1"))
        assertEquals("环境条件已变化", store.freshness(f, "stamp2"))
        clock -= 1; assertEquals("系统时间回退，待复核", store.freshness(f, "stamp1"))
        clock = f.checked + 61000; assertEquals("有效期已过", store.freshness(f, "stamp1"))
        store.invalidate({ it.scope == "android_host_root" }, "授权已变")
        assertEquals("授权已变", store.freshness(store.facts().single(), "stamp1"))
        val oldTime = store.facts().single().checked
        store.invalidate({ true }, "另一个原因")
        assertEquals(oldTime, store.facts().single().checked)
    }
    @Test fun corruptedMarkdownIsNeverSilentlyOverwrittenAndUnsafeFieldsAreRejected() {
        val store = store(); save(store)
        File(temp.root, "environment.md").writeText("broken")
        assertThrows(Exception::class.java) { save(store, text = "不能覆盖") }
        assertEquals("broken", File(temp.root, "environment.md").readText())
        assertThrows(IllegalArgumentException::class.java) { EnvironmentArchive.safe("value\n## fake", 100) }
        assertThrows(IllegalArgumentException::class.java) { EnvironmentArchive.safe("password=secret", 100) }
    }
    @Test fun archiveActionsRejectFabricatedScopeStatusAndPrivateCacheInjection() {
        CapabilityContract.validate(JSONObject().put("action", "lookup").put("query", "驱动"))
        CapabilityContract.validate(JSONObject().put("action", "record").put("key", "custom.hardware.feature").put("title", "新硬件特征")
            .put("conclusion", "待复核的观察").put("source_receipt", "receipt").put("excerpt", "真实片段"))
        listOf(JSONObject().put("action", "record").put("status", "已验证"),
            JSONObject().put("action", "inspect").put("_cached_checks", JSONObject()),
            JSONObject().put("action", "lookup").put("offset", "2")).forEach { args -> assertThrows(IllegalArgumentException::class.java) { CapabilityContract.validate(args) } }
    }
    @Test fun historyRetentionAndDocumentPaginationStayBounded() {
        val store = store()
        repeat(25) { i -> clock++; save(store, text = "驱动版本$i") }
        assertTrue(File(temp.root, "history").listFiles()!!.size <= 20)
        val page = store.view(offset = 100)
        val full = store.view().getString("text")
        assertEquals(full.drop(100), page.getString("text"))
    }
}
