# Hacker root lifecycle prototype

This is development verification infrastructure, not an installed Capri feature or Skill.
It does **not** implement traffic interception, packet injection/capture, proxying, or any network configuration write. It creates only a root-owned temporary directory, a lease, a journal, and a simulated resource marker.

The Android ARM64 native program has a fixed state directory and fixed actions: bounded run (1–30 seconds), status, stop, recover, purge. It has no arbitrary command/path/PID arguments. A supervisor handles deadlines and worker failure; a worker handles supervisor death. Recovery requires an exclusive lease. An inherited lock remains held until both processes exit. State is atomically renamed and synced; cleanup touches only fixed prototype artifacts. It never sends signals to an unrelated process.

`worker_exit` and `supervisor_exit` are deliberate fault-injection modes used only by this prototype. Root services, real network rollback, multi-session integration, and the Skill are not implemented. Simulated cleanup passing does not demonstrate that real network changes can be recovered. Simultaneous forced termination of all helpers or power loss cannot be handled by a surviving-process watchdog; future real operations need their own verified journals/reconciliation.

Run against an explicitly selected test phone with an existing authorized root shell:

```sh
python3 tools/hacker/verify_lifecycle.py --serial DEVICE_SERIAL --adb /path/to/adb --ndk /path/to/ndk
```

The current compiler layout is macOS Android NDK `darwin-x86_64`. Reports and binaries go under ignored `work/hacker-prototype/`. The runner checks deadline cleanup, manual stop/exclusivity, worker failure, and supervisor failure plus stale journal recovery; it compares selected network/SELinux state before/after and removes its device files. It does not install or update the Capri application, clear data, modify rooting configuration, or change SELinux mode.
