#!/bin/bash
# start-chrome-cdp.sh — Start Chromium headless with CDP on port 9222
# Run once at session start (or via cron on wake)

CHROME_BIN="/data/data/com.termux/files/usr/lib/chromium/chrome"
CHROME_DATA_DIR="/data/data/com.termux/files/usr/tmp/chrome-profile"
CDP_PORT=9222

# Kill any existing Chrome using this profile
pkill -f "chrome.*${CHROME_DATA_DIR}" 2>/dev/null || true
sleep 1

# Ensure data dir exists
mkdir -p "$CHROME_DATA_DIR"

# Start Chrome headless with CDP
nohup "$CHROME_BIN" \
  --headless=new \
  --no-sandbox \
  --disable-dev-shm-usage \
  --disable-gpu \
  --remote-debugging-port=$CDP_PORT \
  --remote-allow-origins=* \
  --user-data-dir="$CHROME_DATA_DIR" \
  > /data/data/com.termux/files/usr/tmp/chromium_cdp.log 2>&1 &

echo "Chrome CDP started on port $CDP_PORT (PID: $!)"
sleep 3

# Verify
curl -s http://127.0.0.1:$CDP_PORT/json/version | python3 -c "import sys,json; d=json.load(sys.stdin); print('OK — Chrome', d['Browser']))" 2>/dev/null \
  && echo "CDP is reachable" \
  || echo "Warning: CDP not responding yet, may need retry"