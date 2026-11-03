# Capri agent core

Portable JVM execution kernel. No Android, Compose, Activity, service or device dependency. OkHttp provides bounded model-capacity metadata requests; hosts provide their execution transport.

The module owns the model/tool execution loop, bounded progress feedback, completion guards, tool scheduling, model retry/request budgets, task ownership and scheduling arithmetic. It also owns context estimation, capacity calibration, compaction and archives, model-account selection, revision namespaces, immutable tool registries, schema validation and discovery working sets. Existing package names are retained for source compatibility. Android implements the loop's host ports for context, transport, tool permissions, storage, events and delivery.

## Verify independently

From the repository root, with JDK 17:

```sh
./gradlew -p agent-core test
```

This standalone build does not configure the Android project. The Android app also consumes this project through `implementation(project(":agent-core"))`.

Hosts provide a coroutine dispatcher and runtime `org.json` implementation (Android supplies it). No host may treat successful dispatch as task completion, retry tool effects through transport, infer permission from a trigger, or automatically replay an interrupted occurrence. `AgentLoop.Host.normalizeTool` allows an authorized host bridge to project tool identity for progress accounting; it grants no dispatch permission.

`KernelTasks` owns jobs by scope key and cleans cancelled lazy jobs. Host adapters must establish execution admission before effects. `KernelSchedule` supplies inexact wake-delay and finish-relative recurrence calculations; Android JobScheduler remains responsible for wakeups. The existing persistent job store owns occurrence IDs, pause/cancel and unknown-effect handling. No full-capability unattended execution is enabled by this module.

## Host boundaries

`ContextArchive` requires read/write, sanitization and transition callbacks. The Android adapter supplies AtomicFile, current knowledge/rule redaction and the existing event journal; other hosts must supply equivalent guarantees. `ContextEntry` contains host-rendered Skill and attachment metadata, not file access or authorization. Archive selection protects original user anchors and complete tool exchanges; archives are written before context is replaced. Paths and checkpoint keys remain compatible.

`ModelAccounts` receives encrypted storage and legacy/decorating callbacks. No Android preferences or keystore API enters this module. Endpoint, account and per-chat/new-chat selection semantics remain unchanged. `ModelContexts` keeps its account/endpoint/model-scoped bounded metadata cache; it never saves credentials or provider error bodies.

`ToolDiscovery` owns assembly, bounded schema loading, contract refresh and validation. The app supplies builtin contracts and current session MCP capabilities, then dispatches only after the existing platform permission and cancellation checks. Discovery does not connect services, grant permission or execute effects. `ToolContracts` is the shared schema builder.

Android service lifetime, battery exemption, unlocked-boot opt-in, UI, concrete Skill/MCP/device tools, encrypted storage and event delivery are host responsibilities. Extracting portable policy does not turn Android-only tools into desktop tools.
