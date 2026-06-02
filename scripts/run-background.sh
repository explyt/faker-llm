#!/usr/bin/env bash
# Запускает Faker LLM в background, пишет pid в .run/faker.pid и логи в .run/faker.log.
# Останавливать через scripts/stop.sh.
#
# Окружение (см. scripts/run.sh): PORT, FAKER_POOL_DIR, JAVA_OPTS, JAVA_OPTS_EXTRA, FAKER_PROFILE, FAKER_JFR_DURATION
set -euo pipefail

cd "$(dirname "$0")/.."

JAR=build/libs/faker-llm-all.jar
if [ ! -f "$JAR" ]; then
  echo "[run-background.sh] fat jar not found, building via shadowJar..."
  ./gradlew --quiet shadowJar
fi

mkdir -p .run
PID_FILE=.run/faker.pid
LOG_FILE=.run/faker.log

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "[run-background.sh] already running (pid=$(cat "$PID_FILE")); use scripts/stop.sh first"
  exit 1
fi

ulimit -n 65536 2>/dev/null || true

PORT="${PORT:-8080}"
FAKER_POOL_DIR="${FAKER_POOL_DIR:-pool}"

# Дефолтный набор JVM-флагов под high-concurrency SSE — см. комментарий в scripts/run.sh.
# На JDK 23+ generational ZGC включён по умолчанию (флаг -XX:+ZGenerational удалён из JDK 24+).
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

if [ "${FAKER_PROFILE:-0}" = "1" ]; then
  JFR_DURATION="${FAKER_JFR_DURATION:-120}"
  if [ "$JFR_DURATION" = "0" ]; then
    JFR_OPTS="-XX:StartFlightRecording=filename=.run/faker.jfr,settings=profile,dumponexit=true"
  else
    JFR_OPTS="-XX:StartFlightRecording=duration=${JFR_DURATION}s,filename=.run/faker.jfr,settings=profile"
  fi
  echo "[run-background.sh] JFR enabled -> .run/faker.jfr (duration=${JFR_DURATION}s)"
else
  JFR_OPTS=""
fi

echo "[run-background.sh] starting (PORT=$PORT FAKER_POOL_DIR=$FAKER_POOL_DIR)"
echo "[run-background.sh] JAVA_OPTS=$JAVA_OPTS $JAVA_OPTS_EXTRA $JFR_OPTS"
nohup env PORT="$PORT" FAKER_POOL_DIR="$FAKER_POOL_DIR" \
  java $JAVA_OPTS $JAVA_OPTS_EXTRA $JFR_OPTS -jar "$JAR" > "$LOG_FILE" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"

# Ждём health
for i in $(seq 1 30); do
  sleep 1
  if curl -sf "http://127.0.0.1:$PORT/healthz" > /dev/null 2>&1; then
    echo "[run-background.sh] up after ${i}s — pid=$PID, log=$LOG_FILE"
    exit 0
  fi
done

echo "[run-background.sh] FAILED to become healthy in 30s. Last log lines:"
tail -20 "$LOG_FILE"
exit 1
