# eMudhra weak private-key leak (CVE-2026-111943)

## Summary

On 2026-10-17, researcher Wayne reported to four root programs (Mozilla / Microsoft / Apple / Chrome) that the Indian CA eMudhra leaked private keys for 8 intermediate CA certificates. The keys were not stolen — they were generated so weakly that they are enumerable: the P-384 private keys are integers below 256 (e.g. d=156).

## Affected objects

- Root: `emSign Root TLS CA - G3` (valid until 2049)
- Intermediate CA: `EM DV TLS CA - G3B` (the certificate in this skill's `assets/certs/`, valid until 2040)
- 8 in total: 6 TLS + 2 code signing; the cross-signed Sectigo versions were also revoked.

## Weak-key recovery principle

A P-384 private key d theoretically ranges over 2^384 values. If d is an integer below 256, there are only 256 candidates. Enumerate d in [1,256), compute the public key via elliptic-curve scalar multiplication d·G, and compare against the certificate's public key. `scripts/recover_weak_key.py` does exactly this in under a second.

## Certificate sources

- crt.sh (CT log): `https://crt.sh/?d=<id>` returns PEM directly. Note these intermediate CAs never signed a leaf, so they are not in CT and must be obtained from the discloser.
- Go fix commit testdata: the standard library hardcodes the 8 SPKI hashes (SHA256 fingerprints).
- Mozilla Bugzilla 2083449: the core disclosure; its REST API requires login.

## Key point: browsers have remote revocation, everything else does not

Chrome blocks via CRLSet and Firefox via OneCRL remotely, with no user update needed. But CLI tools, backend services, IoT, and container images use static CA bundles that do not check revocation or CT, leaving the largest attack surface (Go/Python/Rust already removed the relevant roots).

## Two layers to land

1. Layer 1 ARP: redirect target traffic to the attacker. If the target has ARP protection, poisoning becomes one-way and stalls.
2. Layer 2 certificate: forge a certificate chain to a system-trusted root for silent decryption. Revocation checks / pinning / forced CT each cut this off.

Both layers must be undefended to get plaintext. This skill reproduces the layer-2 weapon (weak-key recovery + forged certificate + chain verification); layer 1 relies on `arp_tool`.
