package ai.capri.app.nativebridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootServiceProtocolTest {
    @Test fun boundedRequestsAndOperationContractsRejectAmbiguousCalls() {
        val request=JSONObject().put("request_key","observation_1").put("command","exec sleep 60")
        assertEquals(180,RootServiceProtocol.request(request).lifetime)
        for(value in listOf(0,601,1.5,"60"))assertThrows(Exception::class.java) { RootServiceProtocol.request(JSONObject(request.toString()).put("lifetime_seconds",value)) }
        assertThrows(Exception::class.java) { RootServiceProtocol.request(JSONObject(request.toString()).put("request_key","../escape")) }
        assertThrows(Exception::class.java) { RootServiceProtocol.request(JSONObject(request.toString()).put("command","id\u0000")) }
        validateParams("root_service",JSONObject(request.toString()).put("operation","start"))
        validateParams("frida_service",JSONObject().put("operation","start").put("request_key","probe").put("server_path","re/server"))
        assertThrows(Exception::class.java) { validateParams("frida_service",JSONObject().put("operation","start").put("command","id")) }
        assertThrows(Exception::class.java) { validateParams("root_service",JSONObject().put("operation","stop").put("command","id")) }
    }
    @Test fun watchdogRetainsIdentityGatingLifetimeOutputAndStopBoundaries() {
        val dir=File("/data/user/0/app/files/root-services/fixture")
        val script=RootServiceProtocol.supervisor(dir,File("/workspace/with'quote"),31)
        assertTrue(script.contains("[ \"\$uid\" = 0 ]"))
        assertTrue(script.indexOf("while [ ! -s release ]")<script.indexOf("/system/bin/sh '/data"))
        assertTrue(script.contains('['+" \"\$elapsed\" -lt 31 ]"))
        assertTrue(script.contains("262144"));assertTrue(script.contains("/stop'"));assertTrue(script.contains("kill -KILL -\$\$"))
        assertFalse(script.contains("sleep 41;"))
        val file=File.createTempFile("root-service-",".sh")
        try { file.writeText(script);assertEquals(0,ProcessBuilder("/bin/sh","-n",file.path).start().waitFor()) } finally { file.delete() }
    }
}
