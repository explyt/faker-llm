# Task 12: Load Anomaly Investigation

**Type:** Investigation

## Goal

Понять, почему фейкер не вытянул целевые SLO в чистом прогоне (Task 11, прогон 2):

- Throughput **794 RPS / 7067 dropped iterations** (заказано 1000 RPS)
- p95 **5.9s** (SLO < 3s)
- p99 **6.43s** (SLO < 5s)
- max **7.56s**

Failure rate = 0 (52936 успешных запросов), функционально всё чисто. Проблема в производительности при заявленной нагрузке.

## Гипотезы (в порядке убывания вероятности)

### H1 — long-replies / reasoning entries формируют долгие стримы by-design (наиболее вероятно)

`03-long-replies.json` entries: `ttftMs.max=1500ms`, текст ~700 chars, `chunkSizeChars=2-8`, `interChunkMs=10-50ms`.
Худший случай на одну entry: `1500 + (700/2)*50 = ~19s`. Среднее ожидаемо ~5-6s. **Это полностью совпадает с наблюдаемым p95=5.9s**.

`04-huge-reasoning.json` ещё больше: `ttftMs.max=3000ms`.

**Проверить:** прогнать k6 с пулом, содержащим только `01-short-replies` (TTFT 100-800ms, короткий текст), и измерить p95/p99. Если p95<1s — гипотеза подтверждена, и SLO в task-11 не достижим с пулом task-05 by design.

### H2 — `kotlinx.coroutines.scheduler.max.pool.size=64` слишком мал

Под 1941 streaming VU каждое соединение держит cold flow с `delay()`-ами между чанками. Если scheduler-pool становится bottleneck'ом, новые запросы ждут.

**Проверить:** прогнать с `Dkotlinx.coroutines.scheduler.max.pool.size=256` и сравнить throughput. Если 1000 RPS взят — H2 подтверждена. Если нет — H2 отвергается.

### H3 — Netty event loop / kqueue native transport не настроен

На macOS aarch64 Netty по умолчанию NIO. `netty-transport-native-kqueue:osx-aarch_64` может дать прирост.

**Проверить:** добавить зависимость, прогнать. Сравнить throughput.

### H4 — GC pause stalls

`-Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100` — нормально, но 415MB RSS под нагрузкой при 50k+ запросов с аллокациями (chunkByRange, JSON encoders) может пилить GC.

**Проверить:** включить `-Xlog:gc*:file=/tmp/gc.log` и проанализировать pause-time distribution. Если 99-percentile pause > 50ms — GC влияет на p99.

## What to Do

### Шаг 1 — изолировать дизайн пула (H1)

```bash
mkdir src/main/resources/pool-short-only
cp src/main/resources/pool/01-short-replies.json src/main/resources/pool-short-only/
# rebuild fat jar
FAKER_POOL_DIR=pool-short-only ulimit -n 65536; java ... -jar build/libs/faker-llm-all.jar
k6 run loadtest/faker-load.js
```

Записать throughput / p95 / p99. Сравнить с baseline.

### Шаг 2 — увеличить scheduler pool (H2)

```bash
java -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 \
  -Dkotlinx.coroutines.scheduler.max.pool.size=256 \
  -jar build/libs/faker-llm-all.jar
```

Если throughput подрос — баг настройки. Если нет — пропустить.

### Шаг 3 — async-profiler или JFR snapshot (если шаги 1-2 не решили)

```bash
# async-profiler
asprof -d 30 -f /tmp/faker-profile.html <pid>
# или JFR
jcmd <pid> JFR.start duration=60s filename=/tmp/faker.jfr
```

Открыть профиль, найти hot path. Возможные находки:
- много времени в `kotlinx.serialization.encodeToString`
- spinning на `delay()` / `kotlinx-coroutines-core` internals
- contention на Netty channel pipeline

### Шаг 4 — GC log

```bash
java ... -Xlog:gc*,gc+heap=info:file=/tmp/gc.log \
  -jar build/libs/faker-llm-all.jar
```

Проанализировать (`grep "Pause"` /tmp/gc.log) — если P99 pause > 50ms, увеличить heap / переключить на ZGC.

## Files/Areas

- НЕТ изменений в коде до диагноза. **Сначала измерения, потом гипотезы → правки**.
- Возможные правки после диагноза:
  - `src/main/resources/pool/` — переснаряжение тайминг-параметров (если H1)
  - `build.gradle.kts` — добавить netty kqueue (если H3)
  - `loadtest/README.md` — обновить рекомендации JVM (если H2 или H4)

## Done When

- [ ] Шаги 1-2 выполнены, результаты зафиксированы в PLAN.md
- [ ] Если SLO всё ещё не выдержан — выполнен шаг 3 или 4
- [ ] Определён главный bottleneck (H1/H2/H3/H4) + предложен fix
- [ ] PLAN.md обновлён с финальным диагнозом

## Key Points

- **Не править код вслепую**. Сначала измерить, потом фиксить.
- H1 наиболее вероятна — пул в task-05 формирует p95~6s сам по себе. Если так — это **противоречие в спеке** task-05 vs task-11, не баг кода. Решение: либо новый "performance pool", либо переписать SLO в task-11.
- На macOS возможна вторая итерация теста с тем же `ulimit` показывает другие цифры — TCP TIME_WAIT накапливается. Между прогонами ждать 30 секунд или резетить `sysctl -w net.inet.tcp.msl=1000`.
