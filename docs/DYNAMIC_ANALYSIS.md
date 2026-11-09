# Managed Android runtime analysis

Capri's short `android.root_exec` retains its existing process-group cleanup. Cross-call host services use an explicit lifecycle instead of daemonization or shell escape. The existing REA runtime MCP still owns Frida process discovery, attachment, script loading, message capture, unloading and detachment; Capri does not implement a second instrumentation engine.

## Service contract

`android` actions `root_service` and `frida_service` accept `operation=start|list|status|stop`. Start requires a stable `request_key` and optionally `lifetime_seconds` (30–600, default 180). Identical repeated starts return their existing receipt; changed parameters with the same key fail. A stopped or failed start is not replayed. Status and stop require `service_id`. Services are scoped to the chat's history revision, with one live service per scope and two per device. A service must run in the foreground of its managed group; daemonization is unsupported. This is process ownership, not a security sandbox for arbitrary Root scripts.

Generic `root_service` start also requires `command`, an Android host shell script. It does not elevate PRoot terminals. Logs retain at most 128 KiB per stream through bounded pipe collectors; reads return bounded tail previews and explicit truncation. Filling a stream can terminate its writer with a broken-pipe error, which must not be treated as successful work.

Each launch records intent before invoking su and verifies the supervisor's UID, group identity and process start time before releasing its command. A supervisor watchdog stops the group at its lifetime even if the app dies. Normal stop verifies that the owned group disappeared. Restart does not adopt saved PIDs or replay scripts; it attempts identity-checked cleanup and reports `cleanup_unconfirmed` when verification fails. Cancellation, history revision, chat deletion, memory reset and model disposal request cleanup. A successful user turn may retain the service across subsequent turns until explicit stop or expiry. Up to 128 durable receipts are retained; capacity failure is explicit rather than silently discarding deduplication evidence.

## Frida workflow

1. Enable REA through the existing user control and prepare/activate `capri-rea-runtime` before starting a service. Dependency maintenance must not interrupt a live managed service.
2. Supply an uncompressed Android ARM64 `frida-server` inside the current workspace. `frida_service` never downloads it. Use the version pinned by `assets/rea/frida-package.json`, not a separately installed Python client version.
3. Start with `server_path`, `request_key` and a bounded lifetime. Capri snapshots the binary with a size limit and SHA-256, copies it into its own host temporary directory, checks `--version`, and launches it on an ephemeral loopback port with private authentication. The file digest identifies the supplied artifact; it is not publisher authentication. No patching of Frida, target defense bypass or system-policy modification is added by Capri.
4. Call REA `list_frida_processes`, then `start_frida_session` for the selected PID. Omit `device_id` and `remote`: Capri inserts the managed connection and authentication internally, only for that history scope. Credentials do not enter model-visible arguments/results or history. An explicit external endpoint cannot silently replace an active managed connection.
5. Use existing REA `load_frida_script`, `frida_session_status`, `unload_frida_script` and `close_frida_session`. These calls keep their upstream schemas and evidence semantics. Script execution may change the selected process; service authorization does not expand the user's target scope.
6. Close instrumentation before `frida_service stop`. Inspect `cleanup_verified`; do not assume cancellation or a lost connection rolled back target changes.

Server startup, authenticated protocol connectivity, target attachment and observed script execution are separate stages. A successful native `sleep` control does not establish Android runtime compatibility. Missing SELinux log entries do not exclude a policy issue. A desktop client does not automatically bypass device or target protections. Spawn behavior remains governed by upstream Frida/Android compatibility; this change does not claim to repair `setArgV0` failures.

## Focused verification

`RootServiceProtocolTest` validates request bounds, operation separation, identity gating, watchdog limits and generated shell syntax. `RootServiceDeviceTest#boundedServiceAndFridaAndroidSessionLifecycle` uses dedicated task files and a debug-only ordinary Android service in the separate `:frida_fixture` process. It never attaches to Capri's Agent process or a third-party app. Provision `cache/frida-smoke-server` explicitly with the pinned server before running. The test exercises cross-call lifetime, short-command independence, deduplication/conflicts, scope isolation, authenticated REA discovery, Android attachment, script messages, unload/detach and verified stop. It uses no model API.

The Frida client/server combination and platform observed in this smoke test are sample compatibility evidence, not a claim that every Android application can be instrumented.

### Recorded validation — 2026-11-03

The incremental Debug and AndroidTest builds, two focused JVM tests and the single native case above passed. The final native run also checked scope cancellation, nonzero service exit with no replay on repeated start, and 30-second expiry with verified process-group cleanup. The APK was installed with application data retained. Report: `work/verify/20261103-170318-854322/report.json` (native case approximately 55 seconds). No full suite, model requests, third-party attachment or prior target investigation was run.

The smoke used managed Frida 17.23.2 on both sides. Its Android ARM64 server was explicitly fetched from the official Frida GitHub release over HTTPS, with local SHA-256 `059446d4a3516a6e1f7a703500cec2105f05cc81132e07c0640d5f6e55b245ea`; no publisher-signature verification is claimed. Test cache files and verified-stopped test receipts were removed afterward. App-process death/restart cleanup and history-edit hooks are implemented but were not exercised by killing the user's running application in this test.
