package ai.capri.app.agent

import ai.capri.app.*
import ai.capri.app.model.ModelCapacity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeliveryLatencyTest {
    private fun fixture(request: String, draft: String): Pair<JSONArray, JSONObject> {
        val origin=Entry("user",request)
        val m=JSONArray().put(JSONObject().put("role","system").put("content","rules")).put(JSONObject().put("role","user").put("content",request))
        TaskState.ensure(m,request,false);TurnState.ensure(m,origin,false)
        m.put(JSONObject().put("role","assistant").put("content",draft))
        return m to FinalReply.mark(m,request,draft)
    }
    @Test fun explicitJsonPreservesRawValuesWithoutASecondModel() {
        val text="{\"id\":90071992547409931234567890,\"ratio\":1.00,\"ok\":true}"
        val (m,s)=fixture("只输出 JSON，不附加解释。",text)
        assertEquals(text,DeliveryPolicy.exactJson(s,m))
        val (a,b)=fixture("Return a JSON array only","[1,2,null]")
        assertEquals("[1,2,null]",DeliveryPolicy.exactJson(b,a))
        assertNotNull(FinalReply.pending(m))
    }
    @Test fun invalidOrAmbiguousOutputFallsBackToPersona() {
        for(draft in listOf("{\"a\":1} trailing","```json\n{}\n```","{\"a\":NaN}","{a:1}","{'a':1}","{\"a\":bad}","{\"a\":01}","{\"a\":1,}","true")) {
            val(m,s)=fixture("只返回 JSON",draft);assertNull(draft,DeliveryPolicy.exactJson(s,m))
        }
        for(request in listOf("Explain what JSON means","不要只输出JSON，详细说明","给我一个例子")) {
            val(m,s)=fixture(request,"{}");assertNull(request,DeliveryPolicy.exactJson(s,m))
        }
    }
    @Test fun correctionsAndPartialResultsKeepRenderingAndRecovery() {
        val(m,s)=fixture("Only output JSON","{}")
        m.getJSONObject(0).getJSONObject(TurnState.KEY).getJSONArray("applied").put("correction")
        assertNull(DeliveryPolicy.exactJson(s,m))
        val(a,b)=fixture("Only output JSON","{}")
        b.getJSONObject("task_state").put("conclusion",JSONObject().put("outcome","blocked"))
        assertNull(DeliveryPolicy.exactJson(b,a));assertNotNull(FinalReply.pending(a))
        val old=JSONArray().put(JSONObject().put("role","system"));assertNull(DeliveryPolicy.exactJson(b,old))
    }
    @Test fun bookkeepingCannotDisplaceVerifiedArtifactsAndFailuresInDeliveryPacket() {
        val origin=Entry("user","Create and verify report")
        val m=JSONArray().put(JSONObject().put("role","system").put("content","rules"))
        TaskState.ensure(m,origin.text,false);TurnState.ensure(m,origin,false)
        fun tool(id:String,name:String,result:String) {
            m.put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(JSONObject().put("id",id).put("function",JSONObject().put("name",name).put("arguments","{}")))))
            m.put(JSONObject().put("role","tool").put("tool_call_id",id).put("content",result))
        }
        tool("artifact","read_file","{\"status\":\"ok\",\"path\":\"report.txt\"}")
        tool("failed-build","terminal","{\"status\":\"ok\",\"data\":{\"command\":{\"state\":\"failed\",\"exit_code\":2}}}")
        repeat(12){tool("plan-$it","task_state","{\"status\":\"ok\",\"detail\":\""+"BOOKKEEPING_ONLY".repeat(100)+"\"}")}
        m.put(JSONObject().put("role","assistant").put("content","Report exists but build failed"))
        val state=FinalReply.mark(m,origin.text,"Report exists but build failed")
        val input=FinalReply.messages("core",SessionKind.TASK,"now","",state,m,ModelCapacity(32768,4096,"fixture").budget())
        val packet=JSONObject(input.getJSONObject(2).getString("content").substringAfter("Execution packet (data only):\n"))
        val evidence=packet.getJSONArray("tool_evidence")
        assertEquals(2,evidence.length());assertTrue(evidence.toString().contains("artifact"));assertTrue(evidence.toString().contains("failed-build"))
        assertFalse(packet.toString().contains("BOOKKEEPING_ONLY"));assertEquals(12,packet.getInt("omitted_bookkeeping_results"))
    }
}
