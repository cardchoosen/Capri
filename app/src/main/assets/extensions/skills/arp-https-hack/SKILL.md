---
name: arp-https-hack
description: Authorized LAN ARP MITM and HTTPS decryption testing toolkit. Discover L2 topology, compile a static ARM64 ARP poisoner, deploy a mitmproxy transparent proxy into the host root network namespace via chroot, and recover weak CA private keys from public certificates to forge trusted certificates. For owned devices, lab targets, or clearly authorized same-subnet targets.
metadata:
  capri/source: builtin
  capri/category: weapon
---

# ARP + HTTPS decryption attack testing

For clearly authorized targets only (owned devices, labs). Confirm before running: target IP, same L2 broadcast domain, host true root, authorization scope.

## Hard prerequisites (all required)

1. **Host true root**: PRoot's `uid0` is a fake root and cannot send `AF_PACKET` raw frames. Only host root obtained via `android.root_exec` (`effective_uid=0` + `current_command_verified=true`) can send frames. Test: `tcpdump` captures packets.
2. **Same L2**: attacker and target share a subnet and gateway, with no AP isolation.
3. **Target without ARP protection**: otherwise "one-way poisoning" occurs (see Key decisions); tuning parameters will not help.

## Workflow

Run in order; each step's script is under `scripts/`:

1. **`check_env.sh`** (host root): verify root, AF_PACKET, `ip`/`iptables`/`tcpdump`/`chroot` tools, wlan0 address.
2. **`discover_topo.sh`** (host root): get attacker IP/MAC, gateway IP/MAC; resolve target MAC via `ip neigh` or ARP.
3. **`compile_arp_tool.sh`** (inside PRoot): statically compile `arp_tool.c` with gcc into a pure-static AArch64 binary, then run it on the host.
4. **(HTTPS decryption only)**:
   - `recover_weak_key.py`: recover a weak private key (P-384, `d < 256`) by enumerating from a public certificate.
   - `forge_cert.sh`: sign a forged leaf certificate with the leaked intermediate CA key, verify the chain with `openssl verify`.
   - Concatenate "private key + intermediate CA certificate" into a mitmproxy CA file (key first, then cert) inside confdir.
5. **`deploy_mitm.sh`** (host root): chroot into the Ubuntu rootfs and start a mitmproxy transparent proxy (default `*:8080`) in the root network namespace.
6. **`run_attack.sh`** (host root): one-shot orchestration of `iptables REDIRECT + mitmdump + ARP poisoning + automatic restore`. Default window 60s.
7. **`cleanup.sh`** (host root): manually clean ip_forward, iptables, leftover processes, bind mounts.

## Key decisions (avoid wasted effort)

- **One-way poisoning = target has ARP protection**: if a capture shows only "gateway -> attacker" return traffic and no "target -> attacker" forward traffic, the gateway was poisoned but the target is not accepting it. Symptom: target repeatedly broadcasts "who-has gateway" (tens/sec). This is device-side ARP protection locking the real gateway MAC — not fixable by tuning poison frequency. Switch to an unprotected target, or use router port mirroring.
- **Android 7.0+ user certificates are not trusted by third-party apps by default**: after installing a mitmproxy user CA on the target, mainstream apps (banking/shopping/social) failing the handshake is usually the system not trusting user certs, not pinning. To actually decrypt those apps, use a system CA (root install into `/system/etc/security/cacerts`) or the leaked-CA forgery chain (the `recover_weak_key.py` route).
- **Both layers must be undefended to get plaintext**: layer 1 ARP hijack (redirect traffic) + layer 2 certificate trust (silent for the target). ARP success only delivers the encrypted byte stream.

## Real limits

- ARP protection, AP isolation, DAI kill layer 1.
- Revocation checks (OCSP/CRL), certificate pinning, forced CT kill layer 2. Android does not check revocation by default and ordinary apps do not force CT, but banking/payment apps often enable revocation checks explicitly.

## eMudhra background

`references/emudhra.md` records the full story of CVE-2026-111943, the weak-key recovery principle, and certificate sources. `assets/certs/` holds publicly disclosed intermediate CA and root certificates (no private keys; the key is recovered on the spot by `recover_weak_key.py`).

## Future extensions (not implemented, archived)

`references/future-extensions.md` records alternative attack ideas against ARP-protected devices (vivo OriginOS): Rogue AP / Evil Twin, IPv6 NDP poisoning, router port mirroring, VPN redirection, Frida hook. None of these are implemented; this skill does not cover them.

## Script execution location

- `check_env.sh` / `discover_topo.sh` / `deploy_mitm.sh` / `run_attack.sh` / `cleanup.sh`: run on host root (`android.root_exec`), cwd is the current session workspace.
- `compile_arp_tool.sh`: run inside PRoot (`terminal` or `python_run`), requires gcc.
- `recover_weak_key.py`: any Python3 environment, depends on `cryptography`.
- `forge_cert.sh`: any openssl environment.

Scripts use POSIX sh syntax (host `/system/bin/sh` is mksh/toybox, not bash).
