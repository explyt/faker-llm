#!/usr/bin/env bash
# Быстрый smoke test: проверяет /healthz и оба chat-endpoint'а.
# Сервер должен быть уже поднят (scripts/run-background.sh).
#
# Окружение: BASE_URL (default http://localhost:8080)
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"

pass() { printf "  \033[32m✓\033[0m %s\n" "$1"; }
fail() { printf "  \033[31m✗\033[0m %s\n" "$1"; exit 1; }

# `head -n-1` is GNU-only; portable equivalent that works on macOS BSD coreutils too.
strip_last_line() { sed '$d'; }

echo "[smoke] target: $BASE"

# 1. healthz
echo "→ GET /healthz"
RESP="$(curl -sS -w '\n%{http_code}' "$BASE/healthz")"
BODY="$(printf '%s' "$RESP" | strip_last_line)"
CODE="$(printf '%s' "$RESP" | tail -n1)"
[ "$CODE" = "200" ] && [ "$BODY" = "ok" ] && pass "healthz=200 'ok'" || fail "healthz: code=$CODE body=$BODY"

# 2. OpenAI non-streaming с force_tag:short (детерминированный success)
echo "→ POST /v1/chat/completions (non-stream, force_tag:short)"
RESP="$(curl -sS -w '\n%{http_code}' "$BASE/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"model":"smoke","messages":[{"role":"user","content":"[[faker:force_tag:short]] hi"}]}')"
CODE="$(printf '%s' "$RESP" | tail -n1)"
BODY="$(printf '%s' "$RESP" | strip_last_line)"
[ "$CODE" = "200" ] || fail "openai non-stream: code=$CODE body=$(printf '%s' "$BODY" | head -c 200)"
echo "$BODY" | grep -q '"object":"chat.completion"' || fail "openai non-stream: missing object marker"
echo "$BODY" | grep -q '"finish_reason":"stop"' || fail "openai non-stream: missing finish_reason=stop"
pass "openai non-stream OK"

# 3. Anthropic non-streaming с force_tag:short
echo "→ POST /v1/messages (non-stream, force_tag:short)"
RESP="$(curl -sS -w '\n%{http_code}' "$BASE/v1/messages" \
  -H 'Content-Type: application/json' \
  -d '{"model":"smoke","max_tokens":1024,"messages":[{"role":"user","content":"[[faker:force_tag:short]] hi"}]}')"
CODE="$(printf '%s' "$RESP" | tail -n1)"
BODY="$(printf '%s' "$RESP" | strip_last_line)"
[ "$CODE" = "200" ] || fail "anthropic non-stream: code=$CODE body=$(printf '%s' "$BODY" | head -c 200)"
echo "$BODY" | grep -q '"type":"message"' || fail "anthropic non-stream: missing type=message"
echo "$BODY" | grep -q '"stop_reason":"end_turn"' || fail "anthropic non-stream: missing stop_reason=end_turn"
pass "anthropic non-stream OK"

# 4. Force HTTP 429 (OpenAI shape)
echo "→ POST /v1/chat/completions (force_status:429)"
RESP="$(curl -sS -w '\n%{http_code}' "$BASE/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"model":"smoke","messages":[{"role":"user","content":"[[faker:force_status:429]] x"}]}')"
CODE="$(printf '%s' "$RESP" | tail -n1)"
BODY="$(printf '%s' "$RESP" | strip_last_line)"
[ "$CODE" = "429" ] || fail "force_status 429: code=$CODE body=$BODY"
echo "$BODY" | grep -q '"type":"rate_limit_error"' || fail "force_status 429: missing rate_limit_error"
pass "force_status:429 OK"

# 5. Streaming finishes with [DONE]
echo "→ POST /v1/chat/completions (stream, force_tag:short)"
STREAM_OUT="$(mktemp)"
curl -sS -N "$BASE/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"model":"smoke","stream":true,"messages":[{"role":"user","content":"[[faker:force_tag:short]] hi"}]}' \
  > "$STREAM_OUT"
LAST_DATA="$(grep '^data:' "$STREAM_OUT" | tail -n1)"
[ "$LAST_DATA" = "data: [DONE]" ] || fail "stream did not end with [DONE], last: $LAST_DATA"
FRAMES="$(grep -c '^data:' "$STREAM_OUT")"
pass "openai stream OK ($FRAMES frames, last=[DONE])"
rm -f "$STREAM_OUT"

echo
echo "[smoke] all checks passed against $BASE"
