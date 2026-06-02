#!/usr/bin/env bash
# Обёртка над docker / docker-compose для Faker LLM.
#
# Команды:
#   build         — собрать образ faker-llm:<version>
#   up            — поднять контейнер в background (docker compose up -d)
#   up-fg         — поднять в foreground (логи в терминал)
#   down          — остановить и удалить контейнер
#   restart       — down + up
#   logs          — tail логов контейнера
#   shell         — войти в контейнер shell-ом (полезно для дебага)
#   smoke         — health-check + один тестовый /v1/chat/completions запрос
#   ps            — статус контейнера
#
# Окружение (читается из shell или .env рядом с docker-compose.yml):
#   PORT             — публикуемый порт (default 8080)
#   FAKER_POOL_DIR   — какой pool overlay загружать (default pool)
#   JAVA_OPTS        — JVM-флаги
#
# Примеры:
#   scripts/docker.sh build
#   FAKER_POOL_DIR=pool-deepseek scripts/docker.sh up
#   scripts/docker.sh smoke
#   scripts/docker.sh down

set -euo pipefail

cd "$(dirname "$0")/.."

# Определяем compose-команду: новый docker compose vs legacy docker-compose
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "[docker.sh] neither 'docker compose' nor 'docker-compose' found in PATH" >&2
  exit 1
fi

CMD="${1:-help}"
shift || true

case "$CMD" in
  build)
    echo "[docker.sh] building image..."
    "${COMPOSE[@]}" build "$@"
    ;;
  up)
    echo "[docker.sh] starting in background (FAKER_POOL_DIR=${FAKER_POOL_DIR:-pool})..."
    "${COMPOSE[@]}" up -d "$@"
    "${COMPOSE[@]}" ps
    ;;
  up-fg)
    "${COMPOSE[@]}" up "$@"
    ;;
  down)
    "${COMPOSE[@]}" down "$@"
    ;;
  restart)
    "${COMPOSE[@]}" down
    "${COMPOSE[@]}" up -d
    "${COMPOSE[@]}" ps
    ;;
  logs)
    "${COMPOSE[@]}" logs -f --tail=200 "$@"
    ;;
  shell)
    "${COMPOSE[@]}" exec faker /bin/bash || "${COMPOSE[@]}" exec faker /bin/sh
    ;;
  ps)
    "${COMPOSE[@]}" ps
    ;;
  smoke)
    PORT="${PORT:-8080}"
    BASE="http://127.0.0.1:$PORT"
    echo "[docker.sh] health..."
    curl -fsS "$BASE/healthz" || { echo "FAIL"; exit 1; }
    echo
    echo "[docker.sh] /v1/chat/completions non-streaming..."
    curl -fsS -X POST "$BASE/v1/chat/completions" \
      -H "Content-Type: application/json" \
      -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"hi"}]}' \
      | head -c 200
    echo
    ;;
  help|--help|-h|"")
    sed -n '2,28p' "$0"
    ;;
  *)
    echo "[docker.sh] unknown command: $CMD" >&2
    echo "[docker.sh] run 'scripts/docker.sh help' for usage" >&2
    exit 1
    ;;
esac
