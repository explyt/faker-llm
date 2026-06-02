#!/usr/bin/env bash
# Запускает Faker LLM на :8080 в текущей сессии терминала (foreground).
# Используйте scripts/run-background.sh для daemon-режима.
#
# Окружение:
#   PORT             — порт (default 8080)
#   FAKER_POOL_DIR   — classpath-директория пула (default "pool"; см. pool-clean / pool-short-only / pool-deepseek)
#   FAKER_PROFILE    — "1" чтобы включить JFR-профилирование (файл .run/faker.jfr, settings=profile)
#   FAKER_JFR_DURATION — длительность JFR в секундах (default 120; 0 = до выхода процесса)
#   JAVA_OPTS        — дополнительные JVM-флаги; ПОЛНОСТЬЮ переопределяют дефолтный набор ниже
set -euo pipefail

cd "$(dirname "$0")/.."

JAR=build/libs/faker-llm-all.jar
if [ ! -f "$JAR" ]; then
  echo "[run.sh] fat jar not found, building via shadowJar..."
  ./gradlew --quiet shadowJar
fi

ulimit -n 65536 2>/dev/null || true

PORT="${PORT:-8080}"
FAKER_POOL_DIR="${FAKER_POOL_DIR:-pool}"

# Дефолтный набор JVM-флагов под high-concurrency SSE (~тысячи одновременных стримов).
# Ключевое: ZGC даёт sub-ms паузы (вместо ~100ms у G1) — критично для p95 латенси под нагрузкой.
# На JDK 23+ generational ZGC включён по умолчанию (флаг -XX:+ZGenerational удалён из JDK 24+).
# Перебить целиком можно через JAVA_OPTS, частично — через JAVA_OPTS_EXTRA.
DEFAULT_JAVA_OPTS="\
-Xms4g -Xmx4g \
-XX:+UseZGC \
-XX:+AlwaysPreTouch \
-XX:MaxDirectMemorySize=2g \
-Dio.netty.eventLoopThreads=32 \
-Dkotlinx.coroutines.scheduler.max.pool.size=512 \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=.run/faker-oom.hprof"

JAVA_OPTS="${JAVA_OPTS:-$DEFAULT_JAVA_OPTS}"
JAVA_OPTS_EXTRA="${JAVA_OPTS_EXTRA:-}"

# JFR — включается флагом FAKER_PROFILE=1. Лёгкий профиль (~1% overhead), full method profiling + GC + allocation.
if [ "${FAKER_PROFILE:-0}" = "1" ]; then
  mkdir -p .run
  JFR_DURATION="${FAKER_JFR_DURATION:-120}"
  if [ "$JFR_DURATION" = "0" ]; then
    JFR_OPTS="-XX:StartFlightRecording=filename=.run/faker.jfr,settings=profile,dumponexit=true"
  else
    JFR_OPTS="-XX:StartFlightRecording=duration=${JFR_DURATION}s,filename=.run/faker.jfr,settings=profile"
  fi
  echo "[run.sh] JFR enabled -> .run/faker.jfr (duration=${JFR_DURATION}s)"
else
  JFR_OPTS=""
fi

echo "[run.sh] PORT=$PORT FAKER_POOL_DIR=$FAKER_POOL_DIR"
echo "[run.sh] JAVA_OPTS=$JAVA_OPTS $JAVA_OPTS_EXTRA $JFR_OPTS"

exec env PORT="$PORT" FAKER_POOL_DIR="$FAKER_POOL_DIR" \
  java $JAVA_OPTS $JAVA_OPTS_EXTRA $JFR_OPTS -jar "$JAR"
