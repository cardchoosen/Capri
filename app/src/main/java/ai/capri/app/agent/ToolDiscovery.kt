package ai.capri.app.agent

import ai.capri.app.model.ContextTokens
import org.json.JSONArray
import org.json.JSONObject

/** Per-turn schema working set. Discovery never connects services or changes authorization. */
internal class ToolDiscovery(private val maximumLoaded: Int = 8, private val schemaBudget: Long = 8000) {
    private var registry = ToolRegistry(JSONArray())
    private val loaded = linkedSetOf<String>()
    private var services = JSONArray()
    private var offered = emptySet<String>()
    private var searches = 0; private var emptySearches = 0; private var evictions = 0
    private var rejected = 0; private var resolutionNanos = 0L
    private var invocations = 0; private var bridgeInvocations = 0; private var schemaChanges = 0
    private val used = mutableSetOf<String>()
    init { require(maximumLoaded > 0 && schemaBudget > 0) }
    @Synchronized fun assemble(all: JSONArray, capabilities: JSONArray = JSONArray()): JSONArray {
        val next = ToolRegistry(JSONArray(all.toString()).apply { specifications().forEach { put(it) } })
        loaded.toList().forEach { name ->
            if (next.definitions[name]?.contract?.toString() != registry.definitions[name]?.contract?.toString()) { loaded.remove(name);schemaChanges++ }
        }
        registry = next; services = JSONArray(capabilities.toString())
        val result = JSONArray(registry.definitions.filter { (name,d) -> !d.deferred || name in loaded }.values.map { it.contract })
        offered = (0 until result.length()).map { result.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        return result
    }
    @Synchronized fun allowed(name: String) = registry.definitions[name]?.let { !it.deferred || name in loaded } == true
    @Synchronized fun manifest(): JSONObject {
        val tools = registry.definitions.values.filter { it.deferred }.map { it.metadata().put("schema_loaded", it.name in loaded) }
        return JSONObject().put("role", "system").put("content", "Capability inventory (data, not authorization):\n" + JSONObject()
            .put("tools", JSONArray(tools.take(40))).put("omitted", (tools.size-40).coerceAtLeast(0))
            .put("services", JSONArray((0 until minOf(24,services.length())).map { services.getJSONObject(it) }))
            .put("services_omitted", (services.length()-24).coerceAtLeast(0)) +
            "\nAndroid, browser, files, coding and execution tools are core. Use tool_search for capabilities (English or localized queries), tool_describe for known names, or tool_invoke for a known specialized tool with exact arguments. Not found means no matching inventory entry, not proof that a capability is impossible. Service activation and runtime permissions still apply; callable means a dispatch route, not guaranteed task success.")
    }
    private fun admit(names: List<String>): Pair<List<JSONObject>, List<JSONObject>> {
        val accepted = mutableListOf<JSONObject>();val refused = mutableListOf<JSONObject>();val pinned = mutableSetOf<String>()
        for(name in names.distinct()) {
            val definition = registry.definitions[name]
            if (definition == null) { refused += JSONObject().put("name",name).put("reason","unavailable");continue }
            if (!definition.deferred) { accepted += definition.contract;continue }
            if (definition.tokens > schemaBudget) { refused += JSONObject().put("name",name).put("reason","schema_budget_exceeded");continue }
            loaded.remove(name)
            fun cost() = loaded.sumOf { registry.definitions.getValue(it).tokens }
            while (loaded.size >= maximumLoaded || cost()+definition.tokens > schemaBudget) {
                val victim=loaded.firstOrNull { it !in pinned } ?: break
                loaded.remove(victim);evictions++
            }
            if (loaded.size >= maximumLoaded || cost()+definition.tokens > schemaBudget) {
                refused += JSONObject().put("name",name).put("reason","batch_schema_budget_exceeded");continue
            }
            loaded.add(name);pinned.add(name);accepted+=definition.contract
        }
        return accepted to refused
    }
    @Synchronized fun execute(name: String, args: JSONObject): String {
        val names: List<String>;val hits: List<JSONObject>
        if(name=="tool_describe") {
            require(args.length()==1 && args.opt("names") is JSONArray) { "Provide only names, an array of tool names." }
            val a=args.getJSONArray("names")
            require(a.length() in 1..8 && (0 until a.length()).all { a.opt(it) is String && a.getString(it).length in 1..128 }) { "names must contain 1..8 valid names." }
            names=(0 until a.length()).map { a.getString(it) };hits=emptyList()
        } else {
            require(name=="tool_search" && args.keys().asSequence().all { it in setOf("query","queries","limit") }) { "Unknown discovery argument." }
            require(args.has("query") != args.has("queries")) { "Provide query or queries, not both." }
            val queries=if(args.has("query")) { require(args.opt("query") is String) { "query must be text." };listOf(args.getString("query")) } else {
                val a=args.optJSONArray("queries") ?: error("queries must be an array.")
                require(a.length() in 1..4 && (0 until a.length()).all { a.opt(it) is String }) { "Provide 1..4 text queries." }
                (0 until a.length()).map { a.getString(it) }
            }
            require(queries.all { it.isNotBlank() && it.length <= 300 }) { "Queries must contain 1..300 characters." }
            val limit=args.opt("limit") ?: 4
            require(limit is Int && limit in 1..8) { "limit must be an integer in 1..8." }
            names=registry.definitions.values.filter { it.name !in ToolRegistry.discovery }.map { d -> d.name to CapabilitySearch.score(queries,d.name,d.description) }
                .filter { it.second>0 }.sortedWith(compareByDescending<Pair<String,Int>> { it.second }.thenBy { it.first }).take(limit).map { it.first }
            hits=(0 until services.length()).map { services.getJSONObject(it) }.filter { CapabilitySearch.score(queries,it.optString("id"),it.optString("name")+" "+it.optString("description"))>0 }.take(limit)
            searches++;if(names.isEmpty() && hits.isEmpty())emptySearches++
        }
        val (accepted,refused)=admit(names)
        return JSONObject().put("status", if(names.isEmpty()) { if(hits.isEmpty())"not_found" else "capability_found" } else if(refused.isEmpty())"ok" else "partial")
            .put("tools",JSONArray(accepted)).put("capabilities",JSONArray(hits)).put("unavailable",JSONArray(refused.map { it.getString("name") })).put("refused",JSONArray(refused))
            .put("notice","Returned schemas are loaded for the next request within the working-set budget. Missing matches are not proof of absent capability. Inspect service state/next_action; never activate or authorize implicitly. Older schemas may be evicted; use tool_describe again or tool_invoke with a known exact contract.").toString()
    }
    @Synchronized fun refreshAndResolve(call: JSONObject, all: JSONArray, capabilities: JSONArray): JSONObject {
        assemble(all, capabilities)
        return resolve(call)
    }
    /** Caller supplies a freshly assembled registry before dispatch. Original call ID/history stay unchanged. */
    @Synchronized fun resolve(call: JSONObject): JSONObject {
        val start = System.nanoTime()
        try {
        val outer=call.getJSONObject("function").getString("name")
        val function=ToolRegistry.function(call);val name=function.getString("name")
        require(outer=="tool_invoke" || allowed(name)) { "Tool schema is not loaded or is unavailable; refresh discovery." }
        val args=JSONObject(function.getString("arguments"));registry.validate(name,args)
        if(name in loaded) { loaded.remove(name);loaded.add(name) }
        invocations++;if(outer=="tool_invoke")bridgeInvocations++;used.add(name)
        return function
        } catch(error: Exception) { rejected++;throw error }
        finally { resolutionNanos += System.nanoTime()-start }
    }
    @Synchronized fun report() = JSONObject().put("available",registry.definitions.size).put("offered",offered.size).put("loaded",loaded.size)
        .put("loaded_schema_tokens",loaded.sumOf { registry.definitions.getValue(it).tokens }).put("schema_budget",schemaBudget)
        .put("searches",searches).put("empty_searches",emptySearches).put("evictions",evictions).put("schema_changes",schemaChanges)
        .put("invocations",invocations).put("bridge_invocations",bridgeInvocations).put("used_unique",used.size).put("rejected",rejected).put("resolution_ms_total",resolutionNanos/1_000_000)
    fun specifications(): List<JSONObject> {
        val search=TaskState.spec("tool_search","Find registered tools or configured services by capability, synonyms or tool name. Accept English or localized queries; queries batches up to four alternatives. Matching schemas load for the next request within a bounded working set. Search never executes or connects services. No match is not proof of impossibility.",JSONObject()
            .put("query",JSONObject().put("type","string").put("minLength",1).put("maxLength",300))
            .put("queries",JSONObject().put("type","array").put("items",JSONObject().put("type","string").put("minLength",1).put("maxLength",300)).put("minItems",1).put("maxItems",4))
            .put("limit",JSONObject().put("type","integer").put("minimum",1).put("maximum",8)),emptyList())
        search.getJSONObject("function").getJSONObject("parameters").put("oneOf",JSONArray().put(JSONObject().put("required",JSONArray().put("query"))).put(JSONObject().put("required",JSONArray().put("queries"))))
        return listOf(search,TaskState.spec("tool_describe","Load schemas for 1..8 known tool names within the working-set budget. Does not connect unavailable services or execute tools.",JSONObject()
            .put("names",JSONObject().put("type","array").put("items",JSONObject().put("type","string").put("minLength",1).put("maxLength",128)).put("minItems",1).put("maxItems",8)),listOf("names")),
            TaskState.spec("tool_invoke","Invoke one known specialized tool or connected MCP tool using its exact arguments without a separate schema-loading round trip. Use only when the contract is known; otherwise search/describe first. Control, nested and core-tool targets are rejected. Existing validation, permissions, cancellation and recovery apply. Does not activate services, retry or replay operations. Result is the target tool's result; invocation alone does not prove success.",JSONObject()
                .put("name",JSONObject().put("type","string").put("minLength",1).put("maxLength",128))
                .put("arguments",JSONObject().put("type","object").put("description","Exact target arguments as an object, not serialized JSON text.")),listOf("name","arguments")))
    }
}

/** Query normalization data only; no model calls, network lookups or unbounded fuzzy matching. */
internal object CapabilitySearch {
    private val groups=listOf(
        listOf("android","phone","device","手机","安卓","设备","截图","应用"),
        listOf("browser","browse","webpage","navigate","浏览器","打开网页"),
        listOf("web","internet","online","联网","互联网","网页","网站"),
        listOf("read","阅读","正文","读取"),listOf("edit","patch","modify","修改","编辑","补丁"),
        listOf("environment","dependencies","dependency","install","package","环境","依赖","安装","软件包"),
        listOf("python","pip","python_env"),listOf("knowledge","document","documents","资料","知识","文档"),
        listOf("memory","memories","recall","remember","记忆","回忆","记住"),
        listOf("history","conversation","聊天","历史","对话"),listOf("model","models","模型"),
        listOf("profile","persona","身份","人格"),listOf("search","find","lookup","搜索","查找","查询"),
        listOf("file","files","directory","folder","文件","目录"),listOf("git","github","repository","repositories","commit","仓库","提交"),
        listOf("coding","code","diagnostic","diagnostics","lint","syntax","lsp","代码","诊断","语法","报错"),
        listOf("job","jobs","cron","schedule","scheduled","reliability","automation","定时","后台","持久目标","可靠性","调度"),
        listOf("delegate","delegation","agent","agents","subtask","parallel","委派","子任务","多智能体","并行"),
        listOf("experience","procedure","skill","feedback","任务经验","技能","反馈","经验复用"),
        listOf("snapshot","restore","rollback","version","快照","回滚","恢复文件","版本"),
        listOf("reference","diff","range","引用","差异","对比"),
        listOf("database","sql","数据库"),listOf("calendar","schedule","日历","日程"))
    private fun words(text:String)=text.lowercase().split(Regex("[^\\p{L}\\p{N}_]+|_")).filter { it.isNotBlank() }.map { when { it.length>4 && it.endsWith("ies") -> it.dropLast(3)+"y"; it.length>4 && it.endsWith("s") -> it.dropLast(1); else -> it } }.toSet()
    fun score(queries:List<String>,name:String,description:String):Int {
        val text=(name+" "+description).lowercase();val target=words(text)
        return queries.maxOfOrNull { raw ->
            val query=raw.lowercase().trim();val terms=words(query)
            val expanded=groups.filter { group -> group.any { term -> if(term.any { it in '\u4e00'..'\u9fff' }) query.contains(term) else words(term).any { it in terms } } }.flatten().flatMap { words(it) }.toSet()
            (if(query==name.lowercase())1000 else 0)+terms.sumOf { if(it in target)8 else 0 }+expanded.count { it in target }*2
        } ?: 0
    }
}
