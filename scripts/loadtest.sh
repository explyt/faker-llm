#!/usr/bin/env bash
# Запускает k6 против поднятого Faker LLM.
#
# Окружение:
#   BASE_URL   — URL фейкера (default http://localhost:8080)
#   STAGE      — '1' (loadtest/faker-load.js, baseline по task-11)
#              | '2' (loadtest/faker-load-stage2.js, большие VU pools для 1000+ RPS, default)
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v k6 >/dev/null 2>&1; then
  echo "[loadtest.sh] k6 not installed. Install via 'brew install k6' (macOS) or https://k6.io/docs/getting-started/installation/"
  exit 127
fi

STAGE="${STAGE:-2}"
case "$STAGE" in
  1) SCRIPT=loadtest/faker-load.js ;;
  2) SCRIPT=loadtest/faker-load-stage2.js ;;
  *) echo "[loadtest.sh] unknown STAGE=$STAGE (use 1 or 2)"; exit 2 ;;
esac

BASE_URL="${BASE_URL:-http://localhost:8080}"
ulimit -n 65536 2>/dev/null || true

# Pre-flight: сервер должен отвечать
if ! curl -sf "$BASE_URL/healthz" > /dev/null 2>&1; then
  echo "[loadtest.sh] $BASE_URL/healthz unreachable. Start server first (scripts/run-background.sh)"
  exit 1
fi

echo "[loadtest.sh] running $SCRIPT against $BASE_URL"
BASE_URL="$BASE_URL" exec k6 run --summary-trend-stats='min,med,p(90),p(95),p(99),max' "$SCRIPT"
