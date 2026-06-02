# Load test

k6 + faker-llm: целевая нагрузка **1000 RPS** (700 streaming + 250 non-streaming + 50 tool-calls), 60 секунд.

## 1. Подготовка терминала

```bash
ulimit -n 65536   # обязательно: при 1000 RPS streaming default 256/10k упрётся в socket starvation
```

## 2. Запуск фейкера

Рекомендованный режим — fat jar (без gradle daemon, чтобы JVM-параметры действовали как заказано):

```bash
java \
  -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -Dkotlinx.coroutines.scheduler.max.pool.size=64 \
  -jar build/libs/faker-llm-all.jar
```

Альтернатива (если редактируете код): `gradle run`. Тогда JVM-параметры передаются через `JAVA_OPTS` или `application` блок в `build.gradle.kts` — но gradle daemon добавляет накладные расходы, фейкер на это нечувствителен.

Дождаться в логах: `Application started in …s` + `Responding at http://0.0.0.0:8080`.

## 3. Запуск k6

В отдельной сессии (где тоже `ulimit -n 65536`):

```bash
k6 run loadtest/faker-load.js
```

Опционально кастомный URL:

```bash
BASE_URL=http://127.0.0.1:8081 k6 run loadtest/faker-load.js
```

## 4. Параллельный мониторинг JVM (опционально)

В третьей сессии:

```bash
JPID=$(jps -l | grep faker-llm | awk '{print $1}')
top -pid "$JPID"                   # CPU/MEM live
jcmd "$JPID" Thread.print | wc -l  # ~thread count (corutines не считаются)
jcmd "$JPID" VM.native_memory summary 2>/dev/null || true
```

## 5. SLO

- `http_req_failed.rate < 0.02` — учитывается ~5% инжектированных HTTP-error entries в пуле (это by design, не баг)
- `http_req_duration p95 < 3000ms`
- `http_req_duration p99 < 5000ms`
- Throughput **1000 RPS** все 60 секунд — k6 покажет `dropped_iterations` если генератор не успевает

## 6. Если упёрлись

- `connection refused` → `sudo sysctl -w kern.ipc.somaxconn=4096` (mac listen backlog default 128)
- `read timeout` под streaming → проверить `Dkotlinx.coroutines.scheduler.max.pool.size` (поднять до 128)
- macOS M-series + Netty жалуется на отсутствие kqueue → добавить в `build.gradle.kts`:
  ```kotlin
  implementation("io.netty:netty-transport-native-kqueue:<version>:osx-aarch_64")
  ```
