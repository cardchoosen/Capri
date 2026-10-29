#!/system/bin/sh
# Cleanup (run on host root): disable ip_forward, flush iptables, kill leftover processes, unmount bind mounts
WS=$(pwd)
SESSION=$(dirname "$WS")
ROOTFS="/data/data/ai.capri.app/files/runtime/ubuntu"

# Kill leftover processes for this session (pids written by run_attack.sh/deploy_mitm.sh)
[ -f /data/local/tmp/arp_tool.pid ] && kill "$(cat /data/local/tmp/arp_tool.pid)" 2>/dev/null || true
[ -f /data/local/tmp/mitm.pid ] && kill "$(cat /data/local/tmp/mitm.pid)" 2>/dev/null || true

# Flush iptables redirects
iptables -t nat -D PREROUTING -p tcp --dport 443 -j REDIRECT --to-ports 8080 2>/dev/null || true
iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null || true

# Disable forwarding
echo 0 > /proc/sys/net/ipv4/ip_forward

# Unmount bind mounts
umount "$ROOTFS/root" 2>/dev/null || true
umount "$ROOTFS/workspace" 2>/dev/null || true
umount "$ROOTFS/dev" 2>/dev/null || true
umount "$ROOTFS/proc" 2>/dev/null || true

echo "== cleaned =="
echo "ip_forward=$(cat /proc/sys/net/ipv4/ip_forward)"
echo "-- iptables PREROUTING --"
iptables -t nat -L PREROUTING -n 2>/dev/null | head -5
