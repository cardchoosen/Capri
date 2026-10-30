# Capri agent harness

[简体中文版](AGENT_HARNESS.zh-CN.md)

## Scope and reference

Capri now uses a Kotlin harness inspired by Hermes Agent's separation of turn control, tool dispatch, context compression and recoverable task planning. The Android application remains the host: Hermes's Python runtime is not installed, embedded or launched. This is an independent implementation of the relevant architecture, not a claim of Hermes feature parity or superior agent quality.

Reference inspected on 2026-10-29:

- [Hermes run_agent.py](https://github.com/NousResearch/hermes-agent/blob/main/run_agent.py): turn facade, todo hydration, segmented tool dispatch and stall guidance.
- [Hermes tool_dispatch_helpers.py](https://github.com/NousResearch/hermes-agent/blob/main/agent/tool_dispatch_helpers.py): parallel-safe segments separated by sequential barriers.
- [Hermes conversation_compression.py](https://github.com/NousResearch/hermes-agent/blob/main/agent/conversation_compression.py): context compression lifecycle and cancellation boundaries.
- [Hermes Tool Search](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/tool-search.md): visible capability inventory plus deferred schemas.
- [Hermes license](https://github.com/NousResearch/hermes-agent/blob/main/LICENSE): MIT. No Hermes source or runtime dependency is bundled by this change.

Local reference snapshots and verification artifacts are in ignored `work/hermes-reference/` and `work/agent-harness/2026-10-29/`.

## Runtime boundaries

`agent/AgentLoop.kt` owns the model/tool loop, budget reserve for final delivery, context-rejection recovery, action-preamble correction, task-plan reconciliation and checkpoint boundaries. Its `Host` port provides inference, context assembly, tool execution, persistence and visible events. It has no Activity, Android service or model-provider dependency.

`agent/ModelTransport.kt` provides cancellable HTTPS inference for execution, context summarization and persona delivery. Existing `ModelRequests` owns network retry and its shared per-turn request budget. Tool dispatch is never retried by the transport. Four existing model protocols keep their canonical call/result and signed-payload adapters.

`CapriModel` supplies Android lifecycle, scoped native/file/browser/terminal/MCP adapters, permission checks, UI updates and final persona delivery. The two-stage character design remains: execution creates an internal factual answer; final delivery starts with the Capri character definition and cannot call tools.

## Tool discovery

Core file/coding/execution tools, Skill loading and extension activation stay directly available. Costly specialized built-ins and connected MCP schemas are deferred. A short manifest always advertises deferred tool names and purposes; large catalogs additionally advertise service domains. The manifest is discovery data, not permission.

- `tool_search(query, limit)` searches current names/descriptions using English keywords; default limit 4, maximum 8. It returns matching schemas and loads them for subsequent requests. No matches returns `not_found`.
- `tool_describe(names)` loads up to eight known tool schemas without executing them.
- Each request rebuilds availability from current built-ins and the session's connected MCP tools. Missing tools lose loaded status. Execution rejects unloaded/unavailable names before dispatch; underlying adapters still enforce activation, authorization and runtime restrictions.
- The full Tools catalog remains visible on phone/web, including the discovery tools and task state tool.

Schemas loaded earlier in a turn remain loaded until the turn ends, unless revoked. This first version does not evict active schemas or apply model-based capability routing. Discovery may add a model round trip; measure total task latency as well as schema savings.

## Scheduling and recovery

`ToolScheduler` overlaps consecutive `read_file`, `list_files` and `search_files` calls, with a maximum of four active reads. Workspace file operations use a read/write lock. Every other operation, including unknown/plugin/MCP tools, is a sequential barrier. No arbitrary shell command is assumed read-only.

Proposed calls are saved before dispatch. Each completed result is committed separately, serialized with other result commits, even if a sibling read later fails or is cancelled. Recovery uses the existing unknown-effect placeholders for missing results and never automatically replays a write. Cancellation still propagates to the owning backend; a model request failure does not roll back prior effects.

`ProgressGuard` warns after three identical argument/result observations within a turn. It does not cache, skip, replay or force-stop operations. The warning asks the model to use existing evidence, change its investigation or report a blocker. Ordinary running-terminal reads still use returned offsets and bounded waits.

## Task and context state

`task_state` stores a bounded plan and findings in the existing revision-scoped atomic checkpoint. Updates replace only supplied fields, can guard `expected_revision`, allow at most twenty steps and one in-progress step, and reject invalid updates without modifying the saved state. Simple tasks need no plan. A completed label is an agent assertion, not proof or authorization. A plan with pending work and no blocker gets one reconciliation opportunity before the harness reports incomplete work.

The original task request and recent user context are retained separately from the mutable plan. Private `_capri_*` checkpoint metadata is removed from provider payloads; only explicit bounded task-state data enters requests. Model switches restore task metadata from the canonical checkpoint rather than relying on provider-specific payloads.

`ContextHistory` remains the revision-scoped source for full exchanges and result archives:

1. Ordinary requests reuse the current context while there is headroom.
2. Tool results exceeding 16,000 characters are archived and replaced with valid JSON containing original status when available, head/tail previews, explicit truncation, total size and an `r` reference. Existing private-memory lookups and Skill snapshots retain their specialized treatment. The result archive is retrievable through `context_history`, without rerunning the operation.
3. Around 85% of the estimated input budget, older complete exchanges are archived. The original user goal and current user request remain active. A separate tool-free inference writes a factual handoff covering goals/corrections, decisions, artifacts, verified results, uncertainty, failures and remaining work. Its historical input is explicitly excerpted and bounded.
4. Summary inference uses the same model/account and request budget. Empty, truncated, oversized or otherwise invalid summaries fail explicitly and retain the original recovery checkpoint. Existing atomic exchange archives remain readable. There is no silent semantic-summary fallback that claims all details were preserved.

Complete raw history is not a substitute for active working state. Handoffs are model-generated claims and may omit details; original evidence must be read when needed. Compaction can add latency under pressure. Unfit fixed rules, schemas, a pinned goal or the latest input still fail explicitly rather than silently deleting constraints.

## Delivery and diagnostics

Pending final-response checkpoints include task state, so recent user constraints and unresolved work reach the persona stage. Evidence selection reserves room for recent failures/partial results as well as recent output. Under a tight budget, lower-priority recent results are removed before prioritized failures. Final rendering still rejects empty/truncated responses and tool calls, and continuation resumes delivery without repeating tools.

Numeric `context` trace events report estimated system/tool/total token counts, visible tool count and message count. Existing timings distinguish model requests, final delivery, backoff, environment preparation and tool execution. Estimates are not tokenizer measurements. Headers are not first-token latency. No prompt text, arguments, outputs or credentials are added to performance traces.

## Validation and limits

The 2026-10-29 suite above is historical validation, not a required routine full-suite run. For a focused turn-control build and JVM check, run `./gradlew :app:assembleDebug :app:testDebugUnitTest --tests ai.capri.app.agent.TurnControlTest`. See [turn-control verification](AGENT_TURN_CONTROL.zh-CN.md) for the isolated native case and recorded installation scope. Building is not native validation and does not invoke paid models.

JVM tests exercise the actual loop through scripted inference, execution intent/result checkpoints, malformed/truncated calls, parallel read barriers, cancellation, tool revocation, task-state validation, final handoff constraints, result archive isolation, structured compaction and HTTPS transport. The tool-surface comparison uses the same built-in definitions for eager schemas versus deferred schemas plus manifest; it measures estimated context cost, not task quality or wall-clock speed.

This version retains non-streaming model transport and separate persona delivery. It does not introduce automatic model switching, subagent execution, LSP services, semantic memory indexing or autonomous Skill rewriting. Those require their own task-quality and resource measurements. The Skill content/lifecycle work continues through its existing interfaces.

## Recorded validation: 2026-10-29

- 190 JVM tests and 37 host tests passed. The focused Android profile passed all seven selected cases. Build/deployment report: `work/verify/20261029-191544-838384/report.json`. The debug APK was installed with existing application data retained.
- The same built-in capability fixture measured 12,495 estimated tokens for eager schemas versus 8,253 for deferred schemas plus the visible manifest: 33.95% less estimated context for this surface. This is not an end-to-end speed comparison. The fixture excludes the fixed profile lookup and adds discovery schemas only to the deferred surface.
- A real-model Android task discovered the native tool, read the model/battery and returned the requested JSON. That sample took 9.006 seconds; it is not comparable to earlier different tasks. It also made one unnecessary capabilities probe, prompting more direct guidance for known no-argument native actions.
- A separate real-model coding task initially hit a DNS failure. Continuation created the source, ran four Python assertions successfully and delivered an attachment. After an application-process restart, the persisted checkpoint contained all four successful tool results; a subsequent continuation delivered the requested final JSON with zero new tool calls.
- Numeric summary, source snapshot hashes and limitations: `work/agent-harness/2026-10-29/validation-summary.json`. Model smoke cases used dedicated task chats and did not write to the default personal chat or personal memory.

## Turn control update — 2026-10-30

The working implementation adds checkpointed phases, durable user steering at dispatch boundaries, `ask_user` clarification pauses, request-attempt accounting by purpose with delivery reservation, `task_state finish` evidence assessments, and typed repeated-progress feedback with bounded incomplete exits. User input does not broaden permissions; already-started operations are not rolled back. Completion assessments validate provenance and known failure signals, not arbitrary goal satisfaction. See [the detailed Chinese implementation and verification record](AGENT_TURN_CONTROL.zh-CN.md).
