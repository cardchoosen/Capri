#!/system/bin/sh
# Topology discovery (run on host root)
# Usage: discover_topo.sh [iface] [victim_ip]
# Output attacker/gateway IP+MAC; when victim_ip is given, also resolve its MAC
iface="${1:-wlan0}"
victim_ip="${2:-}"

echo "== attacker ($iface) =="
ip addr show "$iface" 2>/dev/null | grep -E 'inet |link/ether'

echo "== gateway =="
gw=$(ip route | awk '/default/ {print $3; exit}')
echo "gateway_ip=$gw"
if [ -n "$gw" ]; then
  ip neigh show "$gw" 2>/dev/null || echo "(gateway MAC not cached, ping it first)"
fi

if [ -n "$victim_ip" ]; then
  echo "== victim ($victim_ip) =="
  ip neigh show "$victim_ip" 2>/dev/null || echo "(not cached, trigger with arping/ping first)"
fi
