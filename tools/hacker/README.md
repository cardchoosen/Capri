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
