#!/usr/bin/env bash
# Останавливает background-инстанс Faker LLM, поднятый через scripts/run-background.sh.
set -euo pipefail

cd "$(dirname "$0")/.."
PID_FILE=.run/faker.pid

if [ ! -f "$PID_FILE" ]; then
  echo "[stop.sh] $PID_FILE not found — nothing to stop"
  exit 0
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  for i in $(seq 1 10); do
    sleep 1
    if ! kill -0 "$PID" 2>/dev/null; then
      echo "[stop.sh] stopped (pid=$PID)"
      rm -f "$PID_FILE"
      exit 0
    fi
  done
  echo "[stop.sh] still alive after 10s, sending SIGKILL"
  kill -9 "$PID" 2>/dev/null || true
fi
rm -f "$PID_FILE"
echo "[stop.sh] done"
