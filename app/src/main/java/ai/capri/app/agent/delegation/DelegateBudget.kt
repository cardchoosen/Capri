package ai.capri.app.agent.delegation

import org.json.JSONObject

internal class DelegateLimit(message: String): IllegalStateException(message)

/** One ledger per parent turn, shared by all child groups and transport retries. Not a currency quote. */
internal class DelegateBudget(private val maximum: Int=8,private val tokenLimit: Long=48000) {
    private var attempts=0
    private var reserved=0L
    private var actual=0L
    private var unknownUsage=0
    @Synchronized fun reserve(tokens: Long) {
        if(attempts>=maximum || tokens<0 || tokens>tokenLimit-reserved || actual>tokenLimit)throw DelegateLimit("Shared delegation request/token allowance exhausted; continue in the parent or report remaining work.")
        attempts++;reserved+=tokens
    }
    @Synchronized fun observe(usage: JSONObject?,reservedForResponse: Long=0) {
        if(usage==null || !usage.has("prompt_tokens") || !usage.has("completion_tokens")) { unknownUsage++;return }
        val input=usage.opt("prompt_tokens");val output=usage.opt("completion_tokens")
        if(input !is Number || output !is Number || input.toDouble()!=input.toLong().toDouble() || output.toDouble()!=output.toLong().toDouble() || input.toLong() !in 0..10000000L || output.toLong() !in 0..10000000L) { unknownUsage++;return }
        val total=input.toLong()+output.toLong();actual+=total
        reserved+=(total-reservedForResponse).coerceAtLeast(0) // Failed retries retain their original credits; never refund them.
    }
    @Synchronized fun report()=JSONObject().put("attempts",attempts).put("maximum_attempts",maximum).put("reserved_token_credits",reserved).put("token_credit_limit",tokenLimit)
        .put("reported_tokens",actual).put("responses_without_usage",unknownUsage).put("currency_cost","not_estimated")
}
