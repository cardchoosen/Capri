#!/system/bin/sh
# Environment check (run on host root)
# Verify: real root, required tools, wlan0 address, current ip_forward value
echo "== uid =="
id

echo "== tools =="
for t in ip iptables tcpdump chroot mount nsenter; do
  if command -v "$t" >/dev/null 2>&1; then echo "OK  $t"; else echo "MISS $t"; fi
done

echo "== wlan0 =="
ip addr show wlan0 2>/dev/null | grep -E 'inet |link/ether' || echo "no wlan0"

echo "== ip_forward =="
cat /proc/sys/net/ipv4/ip_forward 2>/dev/null

echo "== iptables nat PREROUTING =="
iptables -t nat -L PREROUTING -n 2>/dev/null | head -20
