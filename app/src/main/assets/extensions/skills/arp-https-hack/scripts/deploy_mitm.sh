#!/system/bin/sh
# Deploy a mitmproxy transparent proxy via chroot (run on host root)
# Start mitmdump in the background listening on 8080; log at /data/local/tmp/mitm.log
# Prerequisite: confdir(/workspace/mitmconf) already holds mitmproxy-ca.pem (key+cert concatenated)
set -e

WS=$(pwd)
SESSION=$(dirname "$WS")
SESSION_HOME="$SESSION/home"
ROOTFS="/data/data/ai.capri.app/files/runtime/ubuntu"

# Idempotent: unmount before mounting
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

echo "$!" > /data/local/tmp/mitm.pid
echo "mitmdump pid=$(cat /data/local/tmp/mitm.pid)"
sleep 2
if cat /proc/net/tcp 2>/dev/null | grep -qi ":1F90"; then
  echo "PORT 8080 LISTENING"
else
  echo "PORT 8080 NOT LISTENING — check /data/local/tmp/mitm.log"
fi
