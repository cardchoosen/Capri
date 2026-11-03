package ai.capri.app.agent

import ai.capri.app.TurnCompletion
import ai.capri.app.model.ModelContextRejected
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Model-independent turn controller. Android services, persistence and transport are ports. */
class AgentLoop(private val host: Host) {
    enum class Phase { PREPARING, THINKING, TOOLS, RECOVERING, DELIVERING, WAITING, COMPLETED, INCOMPLETE, BUDGET, CANCELLED, FAILED }
    open class Redirect:Exception("New input requires reassessment before delivery.")
    data class Input(val messages: JSONArray, val tools: JSONArray, val output: Int)
    enum class Outcome { COMPLETE, INCOMPLETE, BUDGET, WAITING }
    interface Host {
        val used: Int
        val maximum: Int
        val request: String
        fun phase(value: Phase) {}
        fun normalizeTool(call:JSONObject):JSONObject=call.getJSONObject("function")
        suspend fun acceptInput(): Boolean = false
        fun canExecute(): Boolean = true
        fun waiting(): Boolean = false
        fun progressState(): JSONObject? = null
        fun saveProgress(value: JSONObject) {}
        fun completeOutcome(): Boolean = true
        suspend fun prepare(): Input
        suspend fun infer(input: Input): JSONObject
        suspend fun rebudget(error: ModelContextRejected, input: Input)
        suspend fun observe(input: Input, response: JSONObject)
        fun append(message: JSONObject)
        suspend fun checkpoint()
        fun decision(choice: JSONObject, count: Int, decision: String, corrections: Int)
        fun preamble(content: String, tools: List<String>)
        fun status(message: String)
        suspend fun execute(call: JSONObject): String
        suspend fun record(call: JSONObject, result: String)
        fun completionIssue(): String? = null
        suspend fun deliver(draft: String)
        suspend fun deliverIncomplete(reason: String) { status(reason) }
    }
    suspend fun run(): Outcome = try { runLoop() }
    catch (cancel: kotlinx.coroutines.CancellationException) {
        host.phase(Phase.CANCELLED)
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { host.checkpoint() }
        throw cancel
    } catch (error: Exception) {
        if (error is ai.capri.app.ModelRequestFailure && error.budgetExhausted) {
            host.status("本轮请求预算已用尽；检查点已保留，可发送“继续”。")
            host.phase(Phase.BUDGET); host.checkpoint(); Outcome.BUDGET
        } else {
        host.phase(Phase.FAILED); host.checkpoint(); throw error
        }
    }
    private suspend fun runLoop(): Outcome {
        var completion = TurnCompletion()
        var adjustments = 0
        var planCorrections = 0
        val progress = ProgressGuard(host.progressState(),host::normalizeTool)
        while (host.used < host.maximum - 1) { // Reserve one request for persona delivery.
            currentCoroutineContext().ensureActive()
            if (host.acceptInput()) { progress.reset(); planCorrections = 0; completion = TurnCompletion() }
            if (host.waiting()) { host.phase(Phase.WAITING); host.checkpoint(); return Outcome.WAITING }
            host.phase(Phase.PREPARING)
            val input = host.prepare()
            host.checkpoint()
            if (host.used >= host.maximum - 1) break
            host.phase(Phase.THINKING)
            val response = try { host.infer(input) } catch (error: ModelContextRejected) {
                if (adjustments++ >= 2) throw IllegalStateException("Context was rejected repeatedly; the checkpoint is preserved.", error)
                host.phase(Phase.RECOVERING)
                host.rebudget(error, input)
                continue
            }
            host.observe(input, response)
            // A response generated before a correction must not dispatch stale proposals.
            if (host.acceptInput()) { progress.reset(); planCorrections = 0; completion = TurnCompletion(); host.checkpoint(); continue }
            val choice = response.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val calls = message.optJSONArray("tool_calls") ?: JSONArray()
            host.decision(choice, calls.length(), "received", completion.corrections)
            if (choice.optString("finish_reason") == "length") {
                host.status("模型输出达到上限；截断的工具参数未执行，检查点已保留，可发送“继续”。")
                host.phase(Phase.BUDGET); host.checkpoint()
                return Outcome.BUDGET
            }
            val priorIds = (0 until input.messages.length()).flatMap { i ->
                val prior = input.messages.getJSONObject(i).optJSONArray("tool_calls") ?: JSONArray()
                (0 until prior.length()).map { prior.getJSONObject(it).optString("id") }
            }.toSet()
            validateCalls(calls, priorIds)
            host.append(message)
            val content = if (message.isNull("content")) "" else message.optString("content")
            val names = (0 until calls.length()).map { calls.getJSONObject(it).getJSONObject("function").getString("name") }
            val decision = if (calls.length() == 0) completion.decide(host.request, content) else TurnCompletion.Decision.ACCEPT
            if (content.isNotBlank() && (calls.length() > 0 || decision != TurnCompletion.Decision.ACCEPT)) host.preamble(content, names)
            if (decision != TurnCompletion.Decision.ACCEPT) {
                val retry = decision == TurnCompletion.Decision.CONTINUE && host.used < host.maximum - 1
                host.decision(choice, 0, if (retry) "continue_preamble" else "incomplete_preamble", completion.corrections)
                host.append(JSONObject().put("role", "system").put("content", TurnCompletion.correction))
                host.checkpoint()
                if (retry) continue
                host.status("任务尚未完成：模型未继续执行。已有结果和检查点已保留，可发送“继续”。")
                host.phase(Phase.INCOMPLETE); host.checkpoint()
                return Outcome.INCOMPLETE
            }
            if (calls.length() == 0) {
                check(content.isNotBlank()) { "Model returned an empty answer; the checkpoint is preserved." }
                val issue = host.completionIssue()
                if (issue != null) {
                    if (planCorrections++ >= 1 || host.used >= host.maximum - 1) {
                        host.phase(Phase.DELIVERING); host.checkpoint()
                        try { host.deliverIncomplete("Completion assessment could not be reconciled after a repair attempt. The task remains partial; summarize verified existing results, unresolved work and any concrete blocker. Do not claim completion or repeat operations. " + issue) }
                        catch (_: Redirect) { progress.reset(); planCorrections = 0; completion = TurnCompletion(); continue }
                        host.phase(Phase.INCOMPLETE); host.checkpoint()
                        return Outcome.INCOMPLETE
                    }
                    host.append(JSONObject().put("role", "system").put("content", issue))
                    host.checkpoint()
                    continue
                }
                host.decision(choice, 0, "accepted_final", completion.corrections)
                host.phase(Phase.DELIVERING); host.checkpoint()
                try { host.deliver(content) } catch (_: Redirect) { progress.reset(); planCorrections = 0; completion = TurnCompletion(); continue }
                val complete = host.completeOutcome()
                host.phase(if (complete) Phase.COMPLETED else Phase.INCOMPLETE)
                return if (complete) Outcome.COMPLETE else Outcome.INCOMPLETE
            }
            // Persist proposed calls first. Recovery marks missing results unknown instead of replaying them.
            host.checkpoint()
            host.phase(Phase.TOOLS)
            ToolScheduler().runControlled(calls, host::execute, { call, result ->
                host.record(call, result)
                progress.observe(call, result)?.let { warning ->
                    host.append(JSONObject().put("role", "system").put("content", warning))
                }
                host.saveProgress(progress.snapshot()); host.checkpoint()
            }, host::canExecute)
            if (host.acceptInput()) { progress.reset(); planCorrections = 0; completion = TurnCompletion(); continue }
            if (host.waiting()) { host.phase(Phase.WAITING); host.checkpoint(); return Outcome.WAITING }
            if (progress.stalled) {
                host.status("任务暂停：多次调用没有带来新进展。已有结果和检查点已保留，请补充信息或发送“继续”后调整方法。")
                host.phase(Phase.INCOMPLETE); host.checkpoint(); return Outcome.INCOMPLETE
            }
        }
        host.status("本轮模型请求预算已用尽；结果已保留，发送“继续”可接续。")
        host.phase(Phase.BUDGET); host.checkpoint()
        return Outcome.BUDGET
    }
    private fun validateCalls(calls: JSONArray, priorIds: Set<String>) {
        require(calls.length() <= 64) { "Tool batch exceeds 64 calls; no call was dispatched." }
        val ids = mutableSetOf<String>()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            require(call.getString("id").isNotBlank() && call.getString("id") !in priorIds && ids.add(call.getString("id"))) { "Tool call IDs must be nonempty and unique; no call was dispatched." }
            require(call.getJSONObject("function").getString("name").isNotBlank()) { "Tool name is required." }
        }
    }
}
