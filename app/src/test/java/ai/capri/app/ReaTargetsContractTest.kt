package ai.capri.app

import ai.capri.app.rea.ReaTargets
import ai.capri.app.extensions.ExtensionInstall
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReaTargetsContractTest {
    @Test fun targetSelectionRequiresOneExplicitTypedSource() {
        ReaTargets.validate(JSONObject("""{"action":"rea_target","package_name":"ai.capri.app"}"""))
        ReaTargets.validate(JSONObject("""{"action":"rea_target","path":"/workspace/app.apk"}"""))
        for (raw in listOf("{}", "{\"path\":null}", "{\"path\":3}", "{\"path\":\"\"}",
            """{"package_name":"ai.capri.app","path":"app.apk"}""",
            """{"package_name":"a; id"}""", """{"path":"app.apk","root":true}""")) {
            assertTrue(raw, runCatching { ReaTargets.validate(JSONObject(raw)) }.isFailure)
        }
    }
    @Test fun contractAdvertisesTargetAcquisitionAndExclusiveInputs() {
        val schema = ExtensionInstall.specification().getJSONObject("function").getJSONObject("parameters")
        assertTrue(schema.getJSONObject("properties").getJSONObject("action").getJSONArray("enum").toString().contains("rea_target"))
        val branches = schema.getJSONArray("oneOf")
        val branch = (0 until branches.length()).map { branches.getJSONObject(it) }.first { it.optJSONObject("properties")?.optJSONObject("action")?.optString("const") == "rea_target" }
        assertEquals(2, branch.getJSONArray("oneOf").length())
        assertFalse(schema.getBoolean("additionalProperties"))
    }
}
