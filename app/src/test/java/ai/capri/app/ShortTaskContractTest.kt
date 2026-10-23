package ai.capri.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShortTaskContractTest {
    @Test fun preservesLegacyCodeAndAcceptsDeclaredRequirementsOrArgvSteps() {
        ShortTaskContract.validate(JSONObject("""{"code":"print(42)","requirements":{"python_packages":["pillow"],"imports":["PIL.Image"]}}"""))
        ShortTaskContract.validate(JSONObject("""{"steps":[{"name":"compile","argv":["g++","main.cpp","-o","main"]}]}"""))
        assertEquals("print(42)", ShortTaskContract.validate(JSONObject().put("code", "print(42)")).getString("code"))
    }
    @Test fun rejectsAmbiguousAndMalformedRequestsBeforeSideEffects() {
        listOf("""{}""", """{"code":"x","steps":[]}""", """{"code":"x","requirements":true}""",
            """{"code":"x","requirements":{"system_packages":["x;id"]}}""",
            """{"steps":[{"name":"compile","argv":"g++ main.cpp"}]}""",
            """{"steps":[{"name":"compile","argv":[""]}]}""",
            """{"code":"x","chat_id":"other"}""").forEach {
            try { ShortTaskContract.validate(JSONObject(it)); fail("Accepted $it") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun outputCannotClaimSuccessWhenTheExecutorReportsNonzeroExit() {
        val result = ShortTaskContract.outcome("exit=7\nlooks successful", 5)
        assertEquals(7, result.getInt("exit_code"))
        assertTrue(result.getBoolean("output_truncated"))
        assertEquals("looks", result.getString("output"))
    }
    @Test fun truncationPreservesJsonAndTheRealFailureExitCode() {
        val result = JSONObject().put("status", "execution_failed").put("exit_code", 9).put("output", "\"\n".repeat(24000))
        val encoded = ShortTaskContract.encode(result)
        assertTrue(encoded.length <= 36000)
        assertEquals(9, JSONObject(encoded).getInt("exit_code"))
        assertTrue(JSONObject(encoded).getBoolean("output_truncated"))
    }

}
