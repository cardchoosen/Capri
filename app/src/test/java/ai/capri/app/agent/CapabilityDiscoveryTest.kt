package ai.capri.app.agent

import ai.capri.app.RecoveryMessages
import ai.capri.app.PerformanceTrace
import ai.capri.app.model.ContextTokens
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CapabilityDiscoveryTest {
    private fun spec(name:String,description:String="Inspect dependencies",size:Int=0)=TaskState.spec(name,description+"x".repeat(size),JSONObject().put("action",JSONObject().put("type","string").put("enum",JSONArray().put("inspect"))),listOf("action"))
    private fun call(name:String,args:JSONObject=JSONObject().put("action","inspect"))=JSONObject().put("id","fixture-call").put("type","function").put("function",JSONObject().put("name",name).put("arguments",args.toString()))
    private fun bridge(name:String,args:JSONObject=JSONObject().put("action","inspect"))=call("tool_invoke",JSONObject().put("name",name).put("arguments",args))
    private fun failure(block:()->Unit) { try { block();fail("Expected rejection") } catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} }
    private fun names(a:JSONArray)=(0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
    @Test fun registryRejectsConflictsAndDefensivelyCopiesContracts() {
        val contract=spec("android");val registry=ToolRegistry(JSONArray().put(contract))
        contract.getJSONObject("function").put("description","mutated")
        registry.definitions.getValue("android").contract.getJSONObject("function").put("description","also mutated")
        assertEquals("Inspect dependencies",registry.definitions.getValue("android").description)
        failure { ToolRegistry(JSONArray().put(spec("android")).put(spec("android","conflict"))) }
    }
    @Test fun highFrequencyCapabilitiesAreEagerAndSpecialistsRemainDeferred() {
        val d=ToolDiscovery();val offered=names(d.assemble(JSONArray().put(spec("android")).put(spec("browser")).put(spec("knowledge")).put(spec("mcp_fixture"))))
        assertTrue(offered.containsAll(listOf("android","browser","tool_invoke")))
        assertFalse("knowledge" in offered);assertFalse("mcp_fixture" in offered)
    }
    @Test fun localizedSynonymsAndBatchedQueriesFindSpecialists() {
        val d=ToolDiscovery();d.assemble(JSONArray().put(spec("python_env","Manage Python packages and dependencies")).put(spec("conversation_history","Read conversation history")))
        val found=JSONObject(d.execute("tool_search",JSONObject().put("queries",JSONArray().put("检查依赖").put("聊天历史"))))
        assertTrue(names(found.getJSONArray("tools")).containsAll(listOf("python_env","conversation_history")))
        assertTrue(CapabilitySearch.score(listOf("dependencies"),"environment","Inspect dependency")>0)
        assertTrue(CapabilitySearch.score(listOf("model_info"),"model_info","") > CapabilitySearch.score(listOf("model_info"),"other","model info"))
    }
    @Test fun localizedTaskQueriesPreferTheRelevantCapability() {
        val all=JSONArray().put(spec("android","Inspect Android device actions"))
            .put(spec("web_search","Search public web information")).put(spec("web_read","Read public web text"))
            .put(spec("search_files","Search workspace files")).put(spec("read_file","Read workspace files"))
            .put(spec("python_env","Manage Python packages and dependencies"))
        val cases=listOf("手机截图" to "android", "联网搜索" to "web_search", "读取网页正文" to "web_read", "搜索文件" to "search_files", "安装 Python 依赖" to "python_env")
        val d=ToolDiscovery();d.assemble(all)
        cases.forEach { (query,expected) ->
            val found=JSONObject(d.execute("tool_search",JSONObject().put("query",query).put("limit",1)))
            assertEquals(query,setOf(expected),names(found.getJSONArray("tools")))
        }
    }
    @Test fun configuredDisconnectedCapabilityIsVisibleWithoutBecomingCallable() {
        val service=JSONObject().put("id","github").put("name","GitHub").put("description","Repository service").put("availability","credentials_required").put("next_action","Configure credentials in Run settings.")
        val d=ToolDiscovery();d.assemble(JSONArray(),JSONArray().put(service))
        val result=JSONObject(d.execute("tool_search",JSONObject().put("query","仓库")))
        assertEquals("capability_found",result.getString("status"));assertEquals(0,result.getJSONArray("tools").length())
        assertEquals("credentials_required",result.getJSONArray("capabilities").getJSONObject(0).getString("availability"))
        failure { d.resolve(bridge("mcp_github_unconnected")) }
        assertFalse(d.allowed("mcp_github_unconnected"))
    }
    @Test fun boundedManifestDoesNotPreventSearchingOmittedEntries() {
        val all=JSONArray();repeat(70){all.put(spec("mcp_item_$it","Unique capability number $it"))}
        val d=ToolDiscovery();d.assemble(all)
        val result=JSONObject(d.execute("tool_search",JSONObject().put("query","mcp_item_69").put("limit",1)))
        assertEquals(setOf("mcp_item_69"),names(result.getJSONArray("tools")))
        assertTrue(d.manifest().getString("content").contains("\"omitted\":30"))
    }
    @Test fun lruWorkingSetIsBoundedAndUseRefreshesRecency() {
        val all=JSONArray().put(spec("mcp_a")).put(spec("mcp_b")).put(spec("mcp_c"));val d=ToolDiscovery(2,2000)
        d.assemble(all)
        d.execute("tool_describe",JSONObject().put("names",JSONArray().put("mcp_a").put("mcp_b")))
        d.resolve(call("mcp_a"))
        d.execute("tool_describe",JSONObject().put("names",JSONArray().put("mcp_c")))
        assertTrue(d.allowed("mcp_a"));assertFalse(d.allowed("mcp_b"));assertTrue(d.allowed("mcp_c"))
        assertEquals(2,d.report().getInt("loaded"));assertEquals(1,d.report().getInt("evictions"))
        assertTrue(d.report().getLong("loaded_schema_tokens")<=2000)
        assertFalse(ToolDiscovery().apply { assemble(all) }.allowed("mcp_a"))
    }
    @Test fun oversizedSchemaIsExplicitlyRefusedWithoutDumpingItIntoContext() {
        val d=ToolDiscovery(2,300);d.assemble(JSONArray().put(spec("mcp_huge",size=10000)))
        val result=JSONObject(d.execute("tool_describe",JSONObject().put("names",JSONArray().put("mcp_huge"))))
        assertEquals("partial",result.getString("status"));assertEquals(0,result.getJSONArray("tools").length())
        assertEquals("schema_budget_exceeded",result.getJSONArray("refused").getJSONObject(0).getString("reason"))
        assertFalse(d.allowed("mcp_huge"));assertTrue(result.toString().length<1200)
        assertEquals("mcp_huge",d.resolve(bridge("mcp_huge")).getString("name"))
    }
    @Test fun schemaChangesAndWithdrawalRevokeDirectCallsImmediately() {
        val d=ToolDiscovery();val first=JSONArray().put(spec("mcp_a"));d.assemble(first)
        d.execute("tool_describe",JSONObject().put("names",JSONArray().put("mcp_a")))
        d.assemble(JSONArray().put(spec("mcp_a","Changed contract")))
        assertFalse(d.allowed("mcp_a"));failure { d.resolve(call("mcp_a")) }
        failure { d.refreshAndResolve(bridge("mcp_a"),JSONArray(),JSONArray()) }
        assertTrue(d.report().getInt("schema_changes")>0)
    }
    @Test fun bridgeAndDirectCallsUseTheSameValidation() {
        val d=ToolDiscovery();d.assemble(JSONArray().put(spec("environment")))
        d.execute("tool_describe",JSONObject().put("names",JSONArray().put("environment")))
        for(c in listOf(call("environment",JSONObject().put("action",42)),bridge("environment",JSONObject().put("action",42)),bridge("environment",JSONObject().put("action","inspect").put("extra",true))))failure { d.resolve(c) }
        assertEquals("environment",d.resolve(bridge("environment")).getString("name"))
        assertEquals(3,d.report().getInt("rejected"))
    }
    @Test fun importedMcpContractsKeepServerValidationSemanticsAndPayloadLimits() {
        val imported=TaskState.spec("mcp_imported","Imported pattern alternatives",JSONObject().put("id",JSONObject().put("oneOf",JSONArray()
            .put(JSONObject().put("type","string").put("pattern","^a"))
            .put(JSONObject().put("type","string").put("pattern","^b")))),listOf("id"))
        val d=ToolDiscovery();d.assemble(JSONArray().put(imported))
        d.execute("tool_describe",JSONObject().put("names",JSONArray().put("mcp_imported")))
        val args=JSONObject().put("id","abc")
        assertEquals(args.toString(),d.resolve(call("mcp_imported",args)).getString("arguments"))
        assertEquals(args.toString(),d.resolve(bridge("mcp_imported",args)).getString("arguments"))
        failure { d.resolve(bridge("mcp_imported",JSONObject().put("id","x".repeat(128001)))) }
        assertEquals(imported.toString(),ToolRegistry(JSONArray().put(imported)).definitions.getValue("mcp_imported").contract.toString())
    }
    @Test fun bridgeRejectsControlNestedCoreAndUnknownTargets() {
        val d=ToolDiscovery();d.assemble(JSONArray().put(spec("android")).put(spec("environment")))
        for(name in listOf("tool_invoke","tool_search","task_state","ask_user","android","not_registered","mcp_missing"))failure { d.resolve(bridge(name)) }
        failure { d.resolve(call("tool_invoke",JSONObject().put("name","environment").put("arguments","{}"))) }
        assertEquals(0,d.report().getInt("invocations"))
    }
    @Test fun bridgeEvidenceCannotPromoteBookkeepingOrMcpFailureToSuccess() {
        val messages=JSONArray().put(JSONObject().put("role","system")).put(JSONObject().put("role","user").put("content","Inspect state"))
        TaskState.ensure(messages,"Inspect state",false)
        messages.put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(bridge("model_info"))))
        messages.put(JSONObject().put("role","tool").put("tool_call_id","fixture-call").put("content","{\"status\":\"ok\"}"))
        assertTrue(CompletionEvidence.results(messages).isEmpty())
        for(raw in listOf("{\"isError\":true}","{\"status\":\"mcp_call_failed\",\"outcome\":\"unknown\"}","{\"status\":\"tool_error\"}"))assertTrue(CompletionEvidence.unsuccessful(raw))
    }
    @Test fun privateBridgeArgumentsAndResultsStayRedactedInRecovery() {
        val messages=JSONArray().put(JSONObject().put("role","system")).put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(bridge("personal_memory",JSONObject().put("text","PRIVATE SECRET")))))
            .put(JSONObject().put("role","tool").put("tool_call_id","fixture-call").put("content","PRIVATE SECRET"))
        val safe=RecoveryMessages.sanitized(messages)
        assertFalse(safe.toString().contains("PRIVATE SECRET"))
        assertEquals("personal_memory",ToolRegistry.function(safe.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0)).getString("name"))
        assertTrue(RecoveryMessages.forResume(JSONArray().put(JSONObject().put("role","system")).put(messages.getJSONObject(1))).toString().contains("结果未记录"))
        assertTrue(messages.toString().contains("PRIVATE SECRET"))
    }
    @Test fun inputShapesValidateReferencesAndActionBranches() {
        val schema=JSONObject("""{"type":"object","additionalProperties":false,"properties":{"count":{"type":"integer","minimum":1},"items":{"type":"array","maxItems":2,"items":{"type":"string"}}},"required":["count"]}""")
        ToolInput.check(JSONObject().put("count",1),schema)
        failure { ToolInput.check(JSONObject().put("count",1.5),schema) }
        failure { ToolInput.check(JSONObject().put("count",0),schema) }
        failure { ToolInput.check(JSONObject().put("count",1).put("items",JSONArray().put(true)),schema) }
        val ref=JSONObject("""{"type":"object","properties":{"id":{"${'$'}ref":"#/${'$'}defs/id"}},"${'$'}defs":{"id":{"type":"string","minLength":2}}}""")
        failure { ToolInput.check(JSONObject().put("id","x"),ref) }
        ToolInput.check(JSONObject().put("id","ok"),ref)
    }
    @Test fun invalidDiscoveryCallsDoNotLoadSchemasOrExecuteAnything() {
        val d=ToolDiscovery();d.assemble(JSONArray().put(spec("environment")))
        for(args in listOf(JSONObject(),JSONObject().put("query",""),JSONObject().put("queries",JSONArray()),JSONObject().put("query","environment").put("limit",1.5),JSONObject().put("query","environment").put("queries",JSONArray().put("environment"))))failure { d.execute("tool_search",args) }
        assertEquals(0,d.report().getInt("loaded"));assertEquals(0,d.report().getInt("invocations"))
        assertEquals("not_found",JSONObject(d.execute("tool_search",JSONObject().put("query","zzzzzz"))).getString("status"))
    }
    @Test fun surfaceMetricsAreNumericAndExcludeArgumentsAndServiceSecrets() {
        val root=Files.createTempDirectory("surface-metrics").toFile()
        try {
            val file=java.io.File(root,"metrics.jsonl");val trace=PerformanceTrace(file)
            trace.toolSurface(JSONObject().put("available",4).put("query","PRIVATE SECRET").put("rejected",1))
            val text=file.readText();assertFalse(text.contains("PRIVATE SECRET"));assertTrue(text.contains("tool_surface"))
            val report=JSONObject(text.lines().filter { it.isNotBlank() }.last()).getJSONObject("counts")
            assertTrue(report.keys().asSequence().all { report.get(it) is Number })
        } finally { root.deleteRecursively() }
    }
    @Test fun fixedInventoryMeasuresCostsCallsAndBoundedLocalLatency() {
        val all=JSONArray();repeat(60){ all.put(spec("mcp_fixture_$it","Repository query capability",500)) }
        val d=ToolDiscovery();val start=System.nanoTime();val eager=d.assemble(all)
        d.execute("tool_search",JSONObject().put("query","mcp_fixture_59").put("limit",1));d.resolve(bridge("mcp_fixture_59"))
        val micros=(System.nanoTime()-start)/1000
        assertEquals(1,d.report().getInt("bridge_invocations"));assertTrue(d.report().getInt("loaded")<=8)
        println("CAPABILITY_FIXTURE "+JSONObject().put("all_schema_tokens",ContextTokens.text(all.toString())).put("eager_schema_tokens",ContextTokens.text(eager.toString())).put("local_us",micros).put("known_contract_model_decisions",1).put("discover_then_call_model_decisions",2).put("provider_calls",0))
    }
}
