# Future extension directions (not currently implemented)

> Status: discussion and partial verification records, **not implemented capabilities**.
> This skill currently covers only "ARP poisoning + HTTPS decryption (eMudhra weak-key recovery route)".
> The content below is an archive of ideas for later extension; no scripts are implemented, and callers must not treat these as supported features.

## Background

While attacking vivo (192.168.1.8, OriginOS), it showed ARP protection: IPv4 ARP poisoning became **one-way** (gateway side succeeded, target side rejected), so target traffic could not be redirected to the attacker. Several alternative paths for "how to get into this device" were discussed; the conclusions are summarized below.

## Status of each approach

### 1. Rogue AP / Evil Twin (physical layer bypass)

- **Idea**: the attacker runs a same-SSID fake AP, deauths the target to force reconnection to the fake AP, and naturally becomes the gateway; all traffic flows through.
- **Verified conclusion**: this device's Wi-Fi chip is Broadcom FullMAC (bcmdhd/dhd driver, PCIe, Samsung Exynos platform). FullMAC runs the MAC-layer management entity (MLME) entirely in chip firmware; the firmware provides no monitor mode or arbitrary frame injection, so deauth cannot be sent. Root cannot change chip firmware capability (the firmware wall is harder than the permission wall).
- **Unmet prerequisite**: an external USB adapter supporting monitor mode + injection (RTL8812AU / RT3070 / AR9271 etc.), plus Android must compile a driver for that adapter.
- **Target-side risk**: vivo may enable PMF (802.11w), which drops forged deauth management frames.

### 2. IPv6 NDP poisoning / RA advertisement (network-layer blind spot)

- **Idea**: IPv4 ARP is defended, but IPv6 uses NDP; if the target app uses IPv6 it could bypass ARP protection.
- **Verified conclusion (dead end on this network)**: this LAN has no IPv6 — attacker wlan0 is link-local only, no IPv6 default route, 0 IPv6 packets in an 8-second capture, no RA advertisements. vivo uses pure IPv4, so this path does not work in the current environment.
- **Note**: re-evaluate on an IPv6-enabled network.

### 3. Router port mirroring (routing-layer bypass)

- **Idea**: log into the router and enable port mirroring to mirror target traffic to the attacker, without touching ARP at all.
- **Unmet prerequisite**: router admin password (`192.168.1.1`).
- **Password acquisition discussion**:
  - Default credentials (admin/admin, admin/password, etc., and the label on the back/bottom) — fastest; a free win if unchanged.
  - Physical reset (hold reset 8-10s) — guaranteed, but drops the whole network and requires reconfiguration (PPPoE account, etc.).
  - Known CVEs (look up auth bypass / command injection by model + firmware version) — luck-dependent; a hit lands in one step.
  - Admin-panel brute force — largely infeasible (rate limiting + lockout + logging).

### 4. VPN redirection (guided traffic)

- **Idea**: the attacker runs a VPN (WireGuard/OpenVPN); the target installs a client and connects; traffic tunnels to the attacker, then a transparent proxy decrypts it.
- **Nature**: from "intrusion" to "guidance"; requires user cooperation but is stable, controllable, and reproducible.
- **Unverified**: requires the target to be able to install and connect a VPN.

### 5. Frida hook (application layer, direct plaintext)

- **Idea**: skip network interception; hook SSL_read/SSL_write or OkHttp on the target and read plaintext directly in memory, fully bypassing the "can't redirect traffic" deadlock.
- **Unmet prerequisite**: target must be rootable (to run frida-server); domestic OriginOS locks the bootloader, making root difficult.

## Conclusion

- For this vivo device, all "technical intrusion" paths are constrained by: device-side ARP protection + attacker hardware (FullMAC, no monitor mode) + network environment (no IPv6).
- Most likely to land: "router port mirroring" (needs password) or "VPN redirection" (needs cooperation).
- Against an unprotected target (e.g. the 192.168.1.14 box), the existing ARP + HTTPS decryption capability can be reused directly.
