package ai.capri.core

import ai.capri.app.agent.AgentLoop
import ai.capri.app.model.ModelContextRejected
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableLoopTest {
    @Test fun hostPortsExecuteAndDeliverWithoutAndroid()=runBlocking {
        var requests=0;var effects=0;var records=0;var checkpoints=0;var reply=""
        val phases=mutableListOf<AgentLoop.Phase>()
        val loop=AgentLoop(object:AgentLoop.Host {
            override val used get()=requests
            override val maximum=8
            override val request="Summarize the fixture."
            override fun phase(value:AgentLoop.Phase){phases+=value}
            override suspend fun prepare()=AgentLoop.Input(JSONArray(),JSONArray(),100)
            override suspend fun infer(input:AgentLoop.Input):JSONObject {
                requests++
                val message=JSONObject().put("role","assistant").put("content",if(requests==1)"" else "Checked result.")
                if(requests==1)message.put("tool_calls",JSONArray().put(JSONObject().put("id","read").put("type","function").put("function",JSONObject().put("name","read_file").put("arguments","{\"path\":\"fixture.txt\"}"))))
                return JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message)))
            }
            override suspend fun rebudget(error:ModelContextRejected,input:AgentLoop.Input)=Unit
            override suspend fun observe(input:AgentLoop.Input,response:JSONObject)=Unit
            override fun append(message:JSONObject)=Unit
            override suspend fun checkpoint(){checkpoints++}
            override fun decision(choice:JSONObject,count:Int,decision:String,corrections:Int)=Unit
            override fun preamble(content:String,tools:List<String>)=Unit
            override fun status(message:String)=Unit
            override suspend fun execute(call:JSONObject):String{effects++;return "{\"status\":\"ok\"}"}
            override suspend fun record(call:JSONObject,result:String){records++}
            override suspend fun deliver(draft:String){reply=draft}
        })
        assertEquals(AgentLoop.Outcome.COMPLETE,loop.run())
        assertEquals(1,effects);assertEquals(1,records);assertEquals("Checked result.",reply)
        assertTrue(checkpoints>0);assertEquals(AgentLoop.Phase.COMPLETED,phases.last())
    }
}
