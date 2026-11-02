---
name: capri-development
description: "Develop or troubleshoot Capri itself: Android app, Agent orchestration, USB web client, runtime integration and the separate documentation repository. Use alongside project-development for changes to Capri source code."
metadata:
  capri/source: builtin
---

Use project-development for repository discovery, scoped rules, exact edits, tests and diff review. Inspect the checked-out repository; this Skill does not establish the current commit or build status.

Capri uses a native Kotlin/Compose Android host and one application UID. PRoot/Ubuntu shares the Android kernel. The phone owns conversations, accounts and execution; the USB loopback web client calls phone services rather than maintaining another chat database. Skills are instructions, not independent privileges.

1. Locate the code repository and read applicable AGENTS.md. Inspect status before editing; preserve unrelated changes.
2. Trace the relevant contract, runtime validation, dispatcher, persistence and both UI surfaces. Keep all first-party tool contracts, Skill instructions and generated Skill resources in English; retain localized conversation/UI text.
3. Implement the smallest complete change. Check phone and web consistency where either exposes the behavior. Historical message editing changes context, not files or external effects.
4. Inspect build prerequisites. An ARM64 phone runtime must not assume it can run desktop Android SDK binaries. Use an authorized connected development environment when available; otherwise run supported checks and clearly identify untested build/device behavior.
5. Run meaningful targeted verification, inspect the final diff and report real evidence. Do not weaken assertions or claim installation merely because packaging succeeded.
6. Update the separate CapriDocs repository when requested: concise Architecture overview, module details under architecture/, versioned Features, Plans and append-only Changelogs. Locate the actual repository and its current state rather than guessing paths.
7. Commit, push, install or publish only within the user's current authorization. Verify results; do not rewrite shared history or replace unrelated work to make a check pass.
