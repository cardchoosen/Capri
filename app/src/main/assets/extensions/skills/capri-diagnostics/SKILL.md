---
name: capri-diagnostics
description: Diagnose Capri task failures, premature stopping, model connectivity, missing Skills, environment dependencies or phone/web synchronization. Gather bounded evidence and distinguish model, tool, runtime and UI failures.
metadata:
  capri/source: builtin
---

Start from the user's observed failure and current task. Reuse relevant, current environment evidence; do not run a complete device inventory for every issue.

- Model: use model_info for the actual model and client budget. A missing tool call is not a tool failure; HTTP success is not proof the user task completed. Ask for bounded execution/decision evidence if it is not available through current tools. Never read model keys, account secrets or private key files.
- Skills: use skill action=list to inspect selected source, revision, permission and discovery warnings. Enabled means available, not body already read. A manual-only definition requires explicit invocation. Read the current body only if relevant; stale snapshots are historical evidence.
- Runtime: use the environment tool with task-specific requirements. Distinguish shared system packages, per-chat dependencies, application UID and separately verified host Root. Do not install unrelated packages or infer permanent limitations from one old failure.
- Execution: inspect existing command status and outputs before recovery. Preserve completed effects; do not replay commands merely because the final model reply disconnected.
- Browser/network: terminal access and WebView access are different paths. Use relevant network/browser evidence; do not infer a firewall or proxy failure without observations.
- Phone/web: the phone is the source of truth. Distinguish a stopped computer listener, disconnected ADB transport, unavailable phone process, authorization failure and rendering failure. Do not send a task twice to test connectivity.

Report observed facts, the likely cause, remaining uncertainty and a bounded next step. Do not upload diagnostics or publish an issue without user authorization. Available tools may not expose every internal log; state that limitation rather than inventing evidence.
