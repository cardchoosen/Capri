# Capri agent core

Portable JVM execution kernel. No Android, Compose, Activity, service, device or network-provider dependency.

The module owns the model/tool execution loop, bounded progress feedback, completion guards, tool scheduling, model retry/request budgets, task ownership and scheduling arithmetic. Existing package names are retained for source compatibility. Android implements the loop's host ports for context, transport, tool permissions, storage, events and delivery.

## Verify independently

From the repository root, with JDK 17:

```sh
./gradlew -p agent-core test
```

This standalone build does not configure the Android project. The Android app also consumes this project through `implementation(project(":agent-core"))`.

Hosts provide a coroutine dispatcher and runtime `org.json` implementation (Android supplies it). No host may treat successful dispatch as task completion, retry tool effects through transport, infer permission from a trigger, or automatically replay an interrupted occurrence. `AgentLoop.Host.normalizeTool` allows an authorized host bridge to project tool identity for progress accounting; it grants no dispatch permission.

`KernelTasks` owns jobs by scope key and cleans cancelled lazy jobs. Host adapters must establish execution admission before effects. `KernelSchedule` supplies inexact wake-delay and finish-relative recurrence calculations; Android JobScheduler remains responsible for wakeups. The existing persistent job store owns occurrence IDs, pause/cancel and unknown-effect handling. No full-capability unattended execution is enabled by this module.
