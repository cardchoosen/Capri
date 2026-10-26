# Hacker root lifecycle prototype

Development verification infrastructure, not an installed Capri feature or Skill. Neither mode implements traffic interception, packet injection/capture, proxying, route changes, forwarding changes, or SELinux changes.

## Modes

- Default `simulated`: creates only a fixed root-owned temporary directory, a lease, a journal, and a simulated resource marker. No firewall changes.
- Explicit `firewall-unreferenced`: builds with `CAPRI_FIREWALL_PROBE` and creates a randomly named filter-table chain with one tagged RETURN rule. No rule points to the chain, and no existing chain or policy is edited. Thus it is a real kernel resource test without a traffic hook. Its state directory and binary are separate from simulated mode.

The Android ARM64 native program has fixed actions: bounded run (1–30 seconds), status, stop, recover, purge. It has no arbitrary command/path/PID arguments. A supervisor handles deadlines and worker failure; a worker handles supervisor death. Recovery requires an exclusive lease. An inherited lock remains held until both processes exit. State is atomically renamed and synced; files must be root-owned regular files, and directory and file opens reject symlinks. Signals target only child processes created by this program.

## Real resource ownership and recovery

`firewall_resource.h` is compiled only into the explicit firewall probe. A random chain name is checked for absence; intent is synced before kernel creation. All iptables subprocesses have fixed arguments, a bounded wait and parent-death termination. Cleanup deletes only the exact tagged rule, then attempts to delete the now-empty chain. It never flushes chains, restores a global rule snapshot, changes built-in policies or adds jumps from existing chains. Extra rules or references cause cleanup to fail and retain the journal for investigation.

Fault modes are development-only: `worker_exit`, `supervisor_exit`, and (firewall build only) `both_exit`. The last intentionally leaves an unreferenced chain to verify that a new process can recover from durable intent after both helpers disappear. The conflict test adds a separately tagged verifier-owned RETURN rule within the probe chain, proves recovery preserves it, then removes that exact verifier-owned rule before completing recovery.

Root services, real traffic-path rollback, multi-session integration, and the Skill are not implemented. Passing the chain probe does not demonstrate recovery of routing, NAT, forwarding or neighbor changes. Simultaneous termination of all helpers or power loss requires subsequent reconciliation; a vanished watchdog cannot perform immediate cleanup.

## Device verification

Run against an explicitly selected phone with an existing authorized root shell:

```sh
python3 tools/hacker/verify_lifecycle.py --serial DEVICE_SERIAL --adb /path/to/adb --ndk /path/to/ndk
python3 tools/hacker/verify_lifecycle.py --serial DEVICE_SERIAL --adb /path/to/adb --ndk /path/to/ndk --resource firewall-unreferenced
```

The current compiler layout is macOS Android NDK `darwin-x86_64`. Reports, rule inventories and binaries go under ignored `work/hacker-prototype/`. Four tests cover simulated mode; six tests cover firewall mode including abrupt loss of both helpers and conflicting-rule preservation. Firewall mode compares the complete filter-table rule inventory before/after; both modes compare selected forwarding and SELinux state. The runner removes its device files on success and reports incomplete cleanup without blanket restoration on failure.

The runner does not install/update Capri, clear application data or change rooting configuration. Existing active tasks/residue are left for investigation rather than automatically stopped. Development ADB Root authorization does not prove the Capri application has Root authorization.

## Isolated routing and forwarding experiment

`netns_forwarding.c` plus `verify_netns.py` exercises real kernel forwarding inside disconnected, temporary network namespaces. An outer supervisor remains in the original namespace. The lab creates two virtual endpoint namespaces and two veth pairs entirely inside the lab; it never moves a physical interface or attaches the lab to Wi-Fi. Every configuration subprocess and sysctl write checks that its namespace differs from the original and has no wlan0. Command arguments and documentation-range addresses are fixed, with no arbitrary target input. Helpers have parent-death termination and the outer supervisor has a deadline.

The experiment first verifies direct links, confirms end-to-end failure with forwarding disabled, enables isolated IPv4 forwarding and verifies end-to-end ICMP, then restores forwarding and recorded per-interface IPv4 parameters and verifies failure again. It removes its virtual routes/addresses/interfaces. Abrupt failures rely on the lifetime of private namespaces to contain/release resources; this is not a test of restoring Pixel's live Wi-Fi routing. Changing ip_forward can alter related IPv4 configuration, so restoring only its boolean value is insufficient as a general strategy.

```sh
python3 tools/hacker/verify_netns.py --serial DEVICE_SERIAL --adb /path/to/adb --ndk /path/to/ndk
```

The verifier compares the original namespace ID, IPv4 policy rules/routes, filter rules, selected IPv4 parameters and SELinux state before/after. Report: ignored `work/hacker-prototype/netns-forwarding-report.json`. It removes the temporary device binary and does not install/update Capri. The experiment does not involve the vivo target or real Wi-Fi traffic, NAT, ARP interception, a proxy, HTTPS or a Skill. It provides controlled kernel/data-plane evidence for subsequent design.

## Single-pair Wi-Fi relay experiment

`local_http_fixture.py` runs a bounded, non-file-serving HTTP fixture on the Mac, serving only the selected vivo source address. A fixed test page generates tiny same-origin requests; logs contain only event type, time, selected source and counters. A public fixture token can be reused on restart. The fixture binds one explicit LAN address, supports an owner-side stop marker and a time limit, and avoids reverse DNS during startup.

`pair_relay.c` is a **fixed developer-lab prototype**, currently pinned to Pixel 192.168.1.8, vivo 192.168.1.10, Mac fixture 192.168.1.16 and wlan0. It is not a general-purpose proxy or installed Capri feature. It validates the interface and both neighbor identities, records recovery intent before intervention, and admits only the selected incoming IPv4 direction with a kernel socket filter. It does not enable promiscuous mode, log payloads, change the gateway mapping, or write host routes/firewall/forwarding parameters.

A single eight-second trial temporarily directs vivo's Mac-bound IPv4 frames through Pixel. A userspace L2 relay preserves IP/TCP contents while forwarding them to the real Mac. Replies normally travel directly from Mac to vivo. The filter admits the whole selected pair direction so other protocols between that same pair are not intentionally blackholed; only fixture TCP/GET counters are interpreted. The experiment does not cover IPv6, Internet gateway traffic, bidirectional proxying, HTTPS decryption, certificate signing or an eMudhra exploit.

The parent waits for a ready worker before sending any redirection announcement. Both processes retain restoration material; the worker has parent-death handling and an independent deadline. Restoration verifies the current interface/neighbor identity before sending corrective information, then observes whether matching frames still reach Pixel. Remaining intent is retained if recovery is uncertain. `recover` is restricted to that validated saved context. These mechanisms do not guarantee immediate restoration if all processes disappear or the device/network changes; active-flow crash recovery still needs dedicated validation.

`preflight` validates scope and attaches the receive filter without announcements or packet reads. `guardcheck` exercises the ready handshake/deadline without ARP announcements, reads or forwarding. `verify_pair_relay.py` requires fresh vivo fixture traffic before starting, then checks fixture continuity, relay counters, recovery observations and unchanged host rules/routes/parameters, and removes device artifacts when safe.

```sh
python3 tools/hacker/verify_pair_relay.py --serial DEVICE_SERIAL --adb /path/to/adb --ndk /path/to/ndk --fixture work/hacker-prototype/FIXTURE_DIRECTORY
```

Observed first trial: 40 forwarded frames, 8 fixture GET-containing frames, zero send errors; 11 fixture requests across the active/cleanup interval, 3 more after stopping; zero selected frames in the 2.5-second post-restoration observation. Host baseline hashes matched and device artifacts were removed. These observations support restored direct access, not a direct read of vivo's neighbor table. The worker-ready handshake was hardened after this trial and exercised using the no-ARP guardcheck rather than repeating active intervention. Reports remain under ignored `work/hacker-prototype/`; this is not an APK/Skill delivery.

## Pair control API and recovery verification

The pair helper now exposes `start` (bounded foreground run; `run` remains an alias), `status`, `stop`, `recover`, `preflight`, `guardcheck`, and `guardstart`. `guardstart` allows the same bounded control path with no ARP announcements, packet reads or forwarding. Status combines the exclusive live lease, recovery-intent presence and an atomically written phase record; the live lease and intent take priority over a stale phase after a crash. Idle status/stop/recover do not create a task directory. Stop writes a fixed request marker rather than accepting a PID. Recovery is idempotent and preserves the previous task outcome when no intent remains.

Intent records explicitly distinguish active and dry modes, so recovering a dry fault never sends corrective ARP frames. Unreadable, symlinked or incompatible intent is rejected instead of treated as successfully recovered. Do not replace a running helper or its journal format; verification refuses existing artifacts.

Fault-injection actions exist only in binaries compiled with `CAPRI_RELAY_FAULT_TESTS`: worker/controller exit, plus their dry variants. They affect only this helper's own processes. `verify_pair_recovery.py` defaults to three dry device cases: start/status/stop plus exclusivity, abrupt worker exit, and abrupt controller exit followed by a new process recovering the journal. Each case checks idempotence, inactive stop and unchanged host baseline. The explicit `--active --fixture DIRECTORY` variant requires fresh continuous vivo test requests and also checks continuity after recovery; it must not be described as passed until actually run.

```sh
python3 tools/hacker/verify_pair_recovery.py --serial DEVICE_SERIAL --adb /path/to/adb --ndk /path/to/ndk
```

This remains developer infrastructure; it is not a shipped enhancement or Skill. Actual-flow normal stop, abrupt worker exit and controller exit followed by new-process journal recovery have now passed for this fixed pair. Simultaneous helper loss, network changes during a run, bidirectional proxying and HTTPS remain separate validation stages.

Recovery verification observations: the first active stop attempt failed the fixture-continuity check and was halted, leaving no helper artifacts; the fixture later resumed after a roughly 29-second gap. Its original per-case relay counters were not preserved, so the cause remains undetermined and that attempt is not counted as passing. The verifier now retains partial rows, errors, timestamps and final baseline checks even on failure, and archives reports by timestamp. The fixture records visibility and timeout counters, uses one bounded fetch at a time, and the active gate requires recent visible-page samples.

The diagnostic repeat passed stop (10 frames/2 GET frames, 3 requests after stop). Separately, worker exit passed (5 frames/1 GET frame, 5 later requests, zero client timeouts) and controller exit plus new-process recovery passed (8 frames/2 GET frames recorded before exit, 5 later requests, zero client timeouts). Reported maximum request gaps in the latter two cases were about 1.13s and 1.32s. Every case preserved the checked host network baseline and removed device artifacts; the Mac fixture was stopped afterward. The controller test validates the combined surviving-worker protection and explicit journal-recovery flow, not an independently observed guarantee of fully autonomous recovery.

## Ordinary-UID HTTP proxy engine prototype

`proxy_runtime.py` reuses Capri's existing Ubuntu/PRoot runtime, with a separate development venv under app-private `cache/hacker-proxy-prototype` (bound as `/lab`). The engine runs as Capri's ordinary Android UID, not root. APK contents, session venvs, system routes, forwarding, trust stores and firewall rules are unchanged. PRoot is not a security boundary; this cache shares the app UID and data access. The prototype requires a debuggable installed Capri package for `run-as`.

The pinned trial uses mitmproxy 12.3.1 on Python 3.12.3 / ARM64. `download_proxy_wheels.py` resolves binary-only Linux ARM64 wheels from official PyPI on the Mac, explicitly replacing host environment markers in that resolver process. It records filenames, sizes and SHA-256 in a manifest. `proxy_runtime.py install-offline --version 12.3.1` checks those hashes, then resolves/installs offline on the phone. This is a development transfer path, not the final component downloader or a publisher-signed artifact system. The observed set contained 42 wheels / 19,390,479 bytes; installed disk usage differs. No dependency archives are committed.

`http_proxy_addon.py` provides a fixed reverse proxy, Pixel `192.168.1.8:18766` → Mac `192.168.1.16:18765`, only for the public fixture path and selected vivo/Pixel sources. It rejects other methods/paths/sources, forces the upstream, marks both request and response, and adds a response JSON marker for browser evidence. Health checks are local to Pixel/Mac. Logs contain counters/status/source, not payloads. No CONNECT, HTTPS decryption, certificate installation or generic proxy is implemented. The engine has a 15-minute fallback deadline and an owner-side stop marker.

Start the Mac fixture with the explicit `--relay 192.168.1.8` option, then use the bounded verification runner after preparing the venv. It refuses an occupied port, owns its engine process, checks Pixel round trip and Mac rejection, optionally holds the listener for vivo, requests shutdown and records shutdown evidence:

```sh
python3 tools/hacker/verify_http_proxy.py --adb /path/to/adb --serial DEVICE_SERIAL --seconds 120 --output work/hacker-prototype/UNIQUE_PROXY_RUN
```

The vivo entry is the Pixel URL printed by the runner. Accessing it directly verifies the proxy engine; it does **not** prove transparent interception of the original Mac URL. Root L2 relay and ordinary-UID proxy remain separate until a separately designed scoped bridge and its recovery are verified. Opening Capri to the foreground correlated with successful ordinary-UID LAN access in this trial; the earlier DNS/TCP failures have not been conclusively attributed. App lifecycle/network policy needs explicit treatment before app integration.

## Fixed original-URL HTTP proxy bridge

The explicit `CAPRI_HTTP_PROXY_TEST` build of `pair_relay.c` includes `fixed_proxy_nat.h`. The normal relay build has no NAT behavior. The proxy build checks the existing engine's fixed health endpoint before creating intent, writes a random owner chain into its durable intent, then creates only one NAT chain, its tagged REDIRECT rule and an exact wlan0/vivo-source/Mac-destination/TCP-18765 PREROUTING jump. It does not change global forwarding, routes, policies or trust stores. The engine's Pixel-source upstream is outside the jump, preventing loops.

The packet receiver leaves selected, unfragmented fixture TCP frames to kernel local delivery instead of also raw-forwarding them. Other admitted pair traffic retains the existing L2 relay behavior. Scope remains the fixed developer fixture, IPv4 and small ordinary HTTP requests; it is not a general transparent proxy or fragmented-traffic guarantee.

The worker is ready before installing the hook and announcing the temporary path. Engine health is rechecked before each periodic announcement; failure ends the active phase. Normal shutdown restores the target's direct neighbor path while engine/NAT remain available, observes late selected frames, stops the worker and removes only its exact tagged rules/empty chain. Extra rules or references cause cleanup failure and journal retention; no flush/global restore is used. The worker attempts NAT cleanup if its parent disappears; new-process recovery also handles the recorded chain. These NAT fault paths are implemented but have **not** been fault-injected in active traffic. Changed network identity can prevent recovery and requires investigation with the journal retained. Previous plain-relay fault evidence is not proxy-fault evidence.

The proxy and normal builds share the same task lock/state directory to prevent overlap. Their intent sizes differ: do not replace binaries while a task or journal exists; the original owning build must recover first. The proxy build emits an extra JSON metrics line (admitted frames/NAT cleanup); the new verifier merges both lines. It reports network configuration mutation even in its no-ARP guard mode, because that mode really creates/removes the scoped NAT rules.

```sh
# Start the bounded Mac fixture and ordinary-UID proxy engine separately first.
python3 tools/hacker/verify_fixed_proxy.py --adb /path/to/adb --serial DEVICE_SERIAL --ndk /path/to/ndk --output work/hacker-prototype/UNIQUE_GUARD
# Only with fresh, visible vivo requests to the original Mac fixture URL:
python3 tools/hacker/verify_fixed_proxy.py --adb /path/to/adb --serial DEVICE_SERIAL --ndk /path/to/ndk --active --fixture work/hacker-prototype/FIXTURE --output work/hacker-prototype/UNIQUE_ACTIVE
```

2026-10-26 observations: no-ARP NAT lifecycle passed; one eight-second original-URL vivo trial passed with 45 admitted fixture TCP frames / 7 GET frames, 7 marked backend probes, zero send errors/client timeout increase, maximum request gap 1.257492s, three direct probes after stopping and zero late selected frames during restoration observation. NAT/filter/routing/policy/selected sysctl/SELinux baselines matched; helper artifacts were removed. Engine-unavailable guard exited 89 without intent or baseline changes. Three normal-build no-ARP recovery regressions passed. Root helper access is still through ADB authorization, not Capri app Root integration; no APK or Skill has been shipped.

The companion engine run was deliberately stopped by the operator before its hold deadline; its runner therefore recorded `Engine exited during hold` and exit 1, while engine exit was 0 and `engine_stopped` was true. Preserve that diagnostic separately from the passing bridge report. Both engine and Mac service were stopped after the bridge test. A successful normal-stop trial does not prove recovery after all helpers disappear, Wi-Fi changes, arbitrary-target operation, HTTPS, or the eMudhra case.
