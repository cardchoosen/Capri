package ai.capri.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapabilityContractTest {
    @Test fun acceptsGenericDependenciesAndExplicitVerification() {
        val request = JSONObject("""{"action":"prepare","requirements":{"commands":["git","g++"],"python_packages":["requests>=2"],"imports":["PIL.Image"],"headers":["sys/types.h"],"libraries":["gif"]},"from_project":true}""")
        assertEquals("prepare", CapabilityContract.validate(request).getString("action"))
        CapabilityContract.validate(JSONObject("""{"action":"verify","command":["python3","-c","print(42)"],"timeout_seconds":10}"""))
    }
    @Test fun rejectsAmbiguousOrInjectedPackageRequests() {
        listOf("""{"action":"prepare","requirements":{"system_packages":["git;id"]}}""",
            """{"action":"inspect","requirements":false}""", """{"action":"verify","command":[]}""",
            """{"action":"verify","command":["true"],"timeout_seconds":41}""",
            """{"action":"inspect","command":["true"]}""",
            """{"action":"prepare","requirements":{"headers":["../secret"]}}""").forEach {
            try { CapabilityContract.validate(JSONObject(it)); fail("Accepted $it") } catch (_: IllegalArgumentException) { }
        }
    }
}
