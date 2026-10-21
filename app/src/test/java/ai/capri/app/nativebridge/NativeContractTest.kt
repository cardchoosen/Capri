package ai.capri.app.nativebridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeContractTest {
    @Test fun oneToolExposesUniqueExplicitActions() {
        val spec = NativeCatalog.spec().getJSONObject("function")
        assertEquals("android", spec.getString("name"))
        val names = NativeCatalog.actions.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue("wifi_info" in names && "request_permissions" in names)
        assertNull(NativeCatalog.find("shell_exec"))
        val enum = spec.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        assertEquals(names.size, enum.length())
    }
    @Test fun unknownParametersAndWrongNumericTypesDoNotExecute() {
        assertThrows(NativeFailure::class.java) { validateParams("wifi_info", JSONObject().put("command", "anything")) }
        assertThrows(NativeFailure::class.java) { JSONObject().put("level", "4").boundedInt("level", 1, 0, 10) }
        assertThrows(NativeFailure::class.java) { JSONObject().put("level", 2.5).boundedInt("level", 1, 0, 10) }
        assertThrows(NativeFailure::class.java) { JSONObject().put("level", 50).boundedInt("level", 1, 0, 10) }
        assertEquals(3, JSONObject().put("level", 3).boundedInt("level", 1, 0, 10))
        assertThrows(NativeFailure::class.java) { JSONObject().put("enabled", "false").boolean("enabled") }
    }
    @Test fun calendarWindowsAreBoundedAndTimeBased() {
        assertEquals(1000L to 5000L, calendarRange(JSONObject().put("start_ms", 1000L).put("end_ms", 5000L)))
        assertThrows(NativeFailure::class.java) { calendarRange(JSONObject().put("start_ms", 5000L).put("end_ms", 1000L)) }
        assertThrows(NativeFailure::class.java) { calendarRange(JSONObject().put("start_ms", 0).put("end_ms", 32 * 86_400_000L)) }
    }
}
