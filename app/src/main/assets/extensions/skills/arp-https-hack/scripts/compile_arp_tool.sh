#!/bin/sh
# Compile the static ARM64 ARP poison tool (run inside PRoot)
# Output arp_tool is a pure-static binary that can run directly on host root
set -e
cd "$(dirname "$0")"
gcc -O2 -static -o arp_tool arp_tool.c
echo "== compiled =="
ls -l arp_tool
file arp_tool 2>/dev/null || true
