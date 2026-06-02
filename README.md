# Faker LLM

HTTP-фейкер LLM-провайдеров на Kotlin + Ktor. Мимикрирует под **OpenAI** (`/v1/chat/completions`) и **Anthropic** (`/v1/messages`) с правдоподобным таймингом (TTFT, inter-chunk delays), стримингом, tool calls, reasoning/thinking блоками и инъекцией ошибок (HTTP 429/500/503/504/529 + mid-stream errors).

**Цель**: нагрузочное тестирование инфры от 1000 RPS без зависимости от настоящих провайдеров.

## TL;DR

```bash
# 1. Сборка fat jar (один раз)
./gradlew shadowJar

# 2. Запуск
./scripts/run-background.sh     # daemon (логи в .run/faker.log)
# или
./scripts/run.sh                # foreground

# 3. (опционально) в отдельном терминале — живые логи с подсветкой
./scripts/logs.sh               # tail -F + ANSI colors

# 4. Проверка
./scripts/smoke.sh              # 5 быстрых curl-сценариев

# 5. Нагрузка (нужен k6: brew install k6)
./scripts/loadtest.sh           # 60s, цель 1000 RPS

# 6. Стоп
./scripts/stop.sh
```

## Архитектура

См. [`docs/architecture.svg`](docs/architecture.svg) — структурная диаграмма пакетов и пайплайна запроса.

Стек: Kotlin 2.3.21 + Ktor 3.5.0 + Netty + kotlinx-coroutines 1.11.0 + kotlinx-serialization 1.11.0. JDK toolchain 21 (foojay-resolver автоматически провизит). Подробности — в memory bank (`.veai/memory/`).

Hexagonal: provider-agnostic ядро (`domain` + `pool` + `routing` + `engine`) + адаптеры (`adapter/openai`, `adapter/anthropic`). Новый провайдер = новый адаптер, ядро не меняется.

## Endpoints

| Метод | Путь | Описание |
|---|---|---|
| GET | `/healthz` | Liveness probe → `200 ok` |
| POST | `/v1/chat/completions` | OpenAI Chat Completions (stream + non-stream, tool_calls, reasoning) |
| POST | `/v1/messages` | Anthropic Messages (stream + non-stream, thinking, tool_use, multi-event SSE) |

## Prompt directives (форсинг сценариев)

Маркеры внутри `messages[].content` или `system`:

| Маркер | Эффект |
|---|---|
| `[[faker:force_id:<id>]]` | Использовать конкретный pool entry |
| `[[faker:force_tag:<tag>]]` | Любой entry с указанным `tag` |
| `[[faker:force_status:<n>]]` | Вернуть HTTP-ошибку с кодом `n` (если есть в пуле) |

Доступные `tag`: `short`, `medium`, `long`, `reasoning`, `mixed`, `tool_call`, `mid_stream_error`, `http_error`.

Поиск маркера case-insensitive, **извлечённое значение сохраняет регистр**. При нескольких маркерах побеждает первый по позиции.

### Примеры

```bash
# Детерминированно короткий ответ, стриминг
curl -N http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"x","stream":true,
       "messages":[{"role":"user","content":"[[faker:force_tag:short]] hi"}]}'

# Anthropic с thinking
curl -N http://localhost:8080/v1/messages \
  -H 'Content-Type: application/json' \
  -d '{"model":"x","max_tokens":1024,"stream":true,
       "messages":[{"role":"user","content":"[[faker:force_tag:reasoning]] think"}]}'

# Принудительный 429
curl -i http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"x","messages":[{"role":"user","content":"[[faker:force_status:429]] x"}]}'
```

Полный набор из 11 сценариев — в [`docs/manual-checks.md`](docs/manual-checks.md).

## Pool overlays

| `FAKER_POOL_DIR=` | Файлов | Entries | Когда использовать |
|---|---|---|---|
| `pool` *(default)* | 8 | 26 | Полный набор: short/medium/long/reasoning/mixed/tool_call + **5% http_error + 5% mid_stream_error** |
| `pool-clean` | 6 | 18 | Только success entries (без error-injection) |
| `pool-short-only` | 2 | 6 | short-replies + tool-calls (max-RPS прогоны) |

Пример: `FAKER_POOL_DIR=pool-short-only ./scripts/run.sh`.

## Скрипты

| Скрипт | Что делает |
|---|---|
| `scripts/run.sh` | Foreground запуск с дефолтными JVM-флагами (Xmx2g, G1GC, scheduler=256) и `ulimit -n 65536` |
| `scripts/run-background.sh` | То же в daemon-режиме, pid в `.run/faker.pid`, логи в `.run/faker.log` |
| `scripts/stop.sh` | Останавливает background-инстанс (SIGTERM → ждёт 10s → SIGKILL) |
| `scripts/logs.sh` | `tail -F` лога с цветными level-токенами (ERROR/WARN/INFO/DEBUG). Полезен для background-сервера (в foreground stdout уже цветной). Целью по умолчанию выступает `logs/faker-llm.log` |
| `scripts/smoke.sh` | 5 быстрых curl-проверок: healthz, OpenAI/Anthropic non-stream, force_status:429, streaming с [DONE] |
| `scripts/loadtest.sh` | Прогон k6 (`STAGE=2` по умолчанию, большие VU pools для 1000+ RPS) |

Все скрипты идемпотентны и принимают env-vars: `PORT`, `FAKER_POOL_DIR`, `JAVA_OPTS`, `BASE_URL`.

## Нагрузочное тестирование

Установка k6: `brew install k6` (macOS) или [k6.io](https://k6.io/docs/getting-started/installation/).

```bash
# 1. Поднять с production-tuned параметрами для 1000+ RPS:
FAKER_POOL_DIR=pool-short-only ./scripts/run-background.sh

# 2. Прогнать k6
./scripts/loadtest.sh
```

**Подтверждённый baseline (M-series macOS, JDK 21)**: **981 RPS / 0 failures / 0 dropped iterations / p99=1.17s** на `pool-short-only`. На полном `pool/` потолок ~739 RPS из-за того что long/reasoning entries формируют 5-7s стримы и требуют тысяч параллельных соединений. Подробности — `loadtest/README.md` и `.tasks/faker-llm-tasks/PLAN.md`.

## Конфигурация

`application.conf` минимальный — только Ktor:
```hocon
ktor {
  deployment { port = 8080; port = ${?PORT} }
  application { modules = [ com.faker.llm.MainKt.module ] }
}
```

Всё поведенческое (формат директив, имя pool-директории по умолчанию, тайминги в JSON) — хардкод/JSON, не HOCON.

### Env vars
| Var | Default | Effect |
|---|---|---|
| `PORT` | 8080 | HTTP port |
| `FAKER_POOL_DIR` | `pool` | classpath-директория пула |
| `JAVA_OPTS` | `-Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Dkotlinx.coroutines.scheduler.max.pool.size=256` | JVM-флаги (используется скриптами) |

## Known limitations (by design)

- **Mid-stream error в non-streaming** → HTTP 200 с обрезанным content. Для явных 5xx в non-stream используй `force_status`.
- **`anthropic-version` header не валидируется.**
- **Auth полностью открыт** — любой `Authorization` или его отсутствие.
- **`force_id` на несуществующий id** → HTTP 500 `pool_misconfigured`. Это feature, клиент видит опечатку.
- **Reasoning в non-streaming OpenAI** оборачивается в `<think>...</think>` (формат не задокументирован OpenAI).

Полный список — `docs/manual-checks.md` + memory bank `faker-llm-known-limitations`.

## Структура проекта

```
.
├── build.gradle.kts                          — Kotlin DSL, плагины, зависимости
├── settings.gradle.kts                       — plugin versions + foojay JDK resolver
├── gradle.properties                         — все версии в одном месте
├── src/main/kotlin/com/faker/llm/
│   ├── Main.kt                               — EngineMain + Application.module
│   ├── domain/                               — sealed PoolEntry / ResponsePart / AbstractStreamEvent ...
│   ├── pool/                                 — PoolLoader, PoolSelector, PoolJson, EmptyPoolException
│   ├── routing/                              — RequestRouter, RoutingPolicy, PromptDirectivePolicy
│   ├── engine/                               — DefaultStreamingEngine, Chunking, CallIdGenerator
│   ├── adapter/openai/                       — DTO + RequestMapper + ResponseMapper + Routes
│   ├── adapter/anthropic/                    — DTO + RequestMapper + ResponseMapper + Routes
│   └── app/                                  — HealthRoute, ErrorHandling (StatusPages)
├── src/main/resources/
│   ├── application.conf                      — Ktor config (минимальный)
│   ├── logback.xml                           — Console appender
│   ├── pool/                                 — Default pool (26 entries)
│   ├── pool-clean/                           — Success only (18 entries)
│   └── pool-short-only/                      — Short+tool (6 entries)
├── loadtest/
│   ├── faker-load.js                         — k6 baseline (по task-11 spec)
│   ├── faker-load-stage2.js                  — k6 с большими VU pools (для 1000+ RPS)
│   └── README.md                             — load-test инструкция и SLO
├── scripts/                                  — run / stop / smoke / loadtest
├── docs/
│   ├── architecture.svg                      — структурная диаграмма
│   └── manual-checks.md                      — 11 curl-сценариев (manual verification)
├── .tasks/faker-llm-tasks/                   — план разработки (PLAN.md + 12 task files)
└── .veai/memory/                             — Memory bank (7 entries)
```

## Production deployment (Ubuntu + Docker)

Локальный baseline на macOS упирается в FD wall ядра на ~1190 RPS (см. `loadtest/faker-load-stage6.js` и memory `faker-llm-load-tuning`). На Linux + поднятые sysctl/ulimit ожидается 3k+ RPS на одной ноде.

### Шаги для prod

1. **Host setup** (только один раз на машине):
   ```bash
   sudo bash scripts/prod-host-setup.sh
   ```
   Скрипт раскатает sysctl (`fs.file-max=2M`, TCP buffers, `tcp_tw_reuse`), `limits.d` (nofile=1M), docker.service override. Идемпотентен. После — `re-login` или `reboot` чтобы /etc/security/limits.d применились к login-сессиям.

2. **Build & start:**
   ```bash
   FAKER_POOL_DIR=pool-deepseek scripts/docker.sh build
   FAKER_POOL_DIR=pool-deepseek scripts/docker.sh up
   ```

3. **Проверка что ulimit поднялся внутри контейнера** (должно быть 1048576):
   ```bash
   docker exec faker-llm sh -c 'ulimit -n'
   ```

4. **Smoke + load:**
   ```bash
   scripts/docker.sh smoke
   # На той же или соседней машине:
   k6 run loadtest/faker-load-stage3.js   # 1000 заказанных RPS на pool-deepseek
   ```

### Дополнительный tuning для максимума

- **host networking** (убирает Docker NAT overhead, +5-15% на 3k+ RPS): в `docker-compose.yml` раскомментировать `network_mode: "host"`. Trade-off: теряется network isolation, ports map игнорируется, faker биндится прямо на :8080 хоста.
- **native Netty epoll** (+20-40% throughput на high-concurrency SSE): сейчас НЕ включён — требует переключения с `EngineMain` на `embeddedServer` с custom `configureBootstrap`. Tracked отдельно; включать если после host-tuning'а ещё нужен boost.
- **JVM**: дефолты в `docker-compose.yml` уже синхронны с проверенным под нагрузкой `scripts/run.sh` (ZGC + scheduler=512 + fixed 4g heap). Переопределяется через env `JAVA_OPTS`.

## Дальнейшие шаги

Если упрётесь в производительность на дефолтном пуле — см. `.veai/memory/faker-llm-load-tuning.md`. Корневой совет: переключиться на `pool-short-only` или передизайнить тайминги в `pool/03-long-replies.json` / `pool/04-huge-reasoning.json` (там TTFT 200-3000ms + узкие чанки 2-8 символов формируют долгие стримы).
