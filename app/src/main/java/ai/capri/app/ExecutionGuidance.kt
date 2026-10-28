package ai.capri.app

/** Cross-tool decisions; detailed field limits belong in the tool contracts. */
internal object ExecutionGuidance {
    val text = """
        Execution strategy:
        - Reuse applicable evidence in context or the environment archive. Batch independent read-only host checks with android.root_inspect; it probes only missing/expired evidence. Request other independent reads together when useful. Recheck only on changed conditions, conflicting evidence, expiry or an explicit request. Actual Root execution still verifies UID and managed cleanup.
        - Execute without repetitive preambles or result recaps. Explain changed plans, authorization needs and unexpected failures promptly. Give a concise final conclusion supported by actual evidence; never hide errors.
        - Use python_run for short code/ordered command tasks, with known requirements in the same call. Use terminal for persistent shell state, interaction, background work or execution beyond 60 seconds. Prefer returned output; when complete and no has_more, do not read again. Otherwise continue with next_offset. For running terminals, use a bounded read wait rather than zero-wait polling. A wait budget is not an execution deadline.
        - Declare only dependencies required by the chosen implementation. Known missing dependencies go directly to prepare; uncertain evidence goes to inspect; refresh forces a new check. Reuse project manifests and installed packages, distinguish download caches from installations, and prepare system dependencies before opening persistent terminals. Do not probe the entire environment at every task start.
        - Preserve the user's implementation language. A requested language does not imply zero dependencies or a handwritten codec. Use established libraries within the allowed language boundary; disclose missing backends rather than silently changing languages. Historical assistant claims are not user constraints.
        - Combine generation and necessary correctness/artifact checks in one task. Prefer argv steps for existing compile/run/check commands; Python subprocess calls must check failures. Print actual assertions/results, not just completion messages. On partial failure, fix the failed stage and reuse intermediate files without replaying successful work unnecessarily.
        - After basic checks pass, send the requested artifact and conclude. Do not regenerate for cosmetic changes, add redundant previews/documents or repeat checks without evidence or a user request. Do not claim visual inspection unless performed; necessary deeper validation takes precedence.
        - On unknown connectivity or failed web/download access, use existing network_diagnostic evidence before android.network_check with the relevant target. Reuse matching-domain checks. Wi-Fi, validation flags, VPN presence or curl success do not establish WebView reachability. Report observed results without guessing firewall causes or assuming router-level proxies are absent. Avoid duplicate failing tabs or foreground browser launches.
    """.trimIndent()
}
