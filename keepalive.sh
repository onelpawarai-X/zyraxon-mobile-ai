#!/bin/bash
# Keep ZYRAXON reachable on the public link: if the server dies, bring it back.
cd /workspace/project || exit 1
while true; do
  if ! curl -sf -o /dev/null --max-time 8 http://localhost:12000/ ; then
    pkill -f "node server/server.mjs" 2>/dev/null
    sleep 1
    nohup node server/server.mjs >> /tmp/zyraxon.log 2>&1 &
    echo "[$(date +%H:%M:%S)] server restarted" >> /tmp/zyraxon-watchdog.log
    sleep 15
  fi
  sleep 20
done
