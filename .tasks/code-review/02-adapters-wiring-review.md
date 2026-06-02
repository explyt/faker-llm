# Adapters & Wiring Review

Scope: `adapter/openai/**`, `adapter/anthropic/**`, `app/*` (ErrorHandling, AppliedTiming, RequestTimer, HealthRoute), `Main.kt`.

## Summary

Адаптеры и обвязка собраны аккуратно: per-adapter `Json`, ручная сериализация через `respondText`/`respondTextWriter`, EmitTimer/markRequestStart/RequestStartNanosKey строго следуют контракту, StatusPages дискриминирует по `/v1/messages`, заголовки `X-Faker-Applied-Timing` + `X-Request-Id` echo'ятся как в memory bank. Главные проблемы — в `app/ErrorHandling.kt`: типы ошибок (`invalid_request`/`internal_error`/`pool_misconfigured`) не совпадают со стандартными провайдерскими (`invalid_request_error`, `server_error`, `api_error`) и не зависят от провайдера, плюс в OpenAI streaming у `tool_calls` всегда `index = 0` (multi-tool-call ломается), и у chunk-frame'ов `finish_reason` не помечен `@EncodeDefault(ALWAYS)` — на wire промежуточные chunks теряют `"finish_reason": null`.

## Findings

### 🔴 Critical
_(нет — все находки tier'ом ниже)_

### 🟠 Major

- **`src/main/kotlin/com/faker/llm/app/ErrorHandling.kt:36,46,56`** — `type`-значения зашиты как `pool_misconfigured` / `invalid_request` / `internal_error`. Memory bank (`faker-llm-header-directive`) фиксирует:
  - OpenAI: 4xx → `invalid_request_error`, 429 → `rate_limit_error`, 5xx → `server_error`.
  - Anthropic: 400 → `invalid_request_error`, 500 → `api_error`, 429 → `rate_limit_error`, …
  Сейчас один и тот же сырой `type` уходит и в OpenAI, и в Anthropic envelope, и ни один из них не соответствует канону. `respondError` уже знает путь (`startsWith(ANTHROPIC_PATH_PREFIX)`), но это знание не прокидывается в выбор `type`. Фикс: вычислять `type` через `openAiErrorTypeFor(status)` / `anthropicErrorTypeFor(status)` (они уже определены в адаптерах) и поделить логику по тому же ветвлению по пути. `SerializationException` → BadRequest+`invalid_request_error`; `EmptyPoolException` → InternalServerError+`server_error`/`api_error`; generic `Throwable` → InternalServerError+`server_error`/`api_error`.

- **`src/main/kotlin/com/faker/llm/adapter/openai/dto/Chunk.kt:29`** — `ChunkChoice.finish_reason: String? = null` без `@EncodeDefault(ALWAYS)`. С `OpenAiJson.encodeDefaults = false` поле выкинут из промежуточных кадров. Реальный OpenAI wire всегда эмитит `"finish_reason": null` до финального chunk'а; клиенты, читающие presence-маркер, поломаются. Фикс: `@EncodeDefault(EncodeDefault.Mode.ALWAYS) val finish_reason: String? = null`.

- **`src/main/kotlin/com/faker/llm/adapter/openai/OpenAiResponseMapper.kt:163-183` + `dto/Chunk.kt:44`** — `ToolCallDelta.index` всегда `0`. При нескольких `ToolCall`-частях в `SuccessEntry.parts` (engine действительно последовательно эмитит `ToolCallStart`/`ToolCallEnd`/`ToolCallStart` — см. `DefaultStreamingEngine.runSuccess`) клиент получает два tool_call'а с одинаковым `index=0`, что в OpenAI протоколе означает «один и тот же вызов» — это конкатенация имени/аргументов вместо двух разных вызовов. Фикс: вести счётчик `toolCallIndex` в `streamSse` (по аналогии с `StreamState.currentIndex` в Anthropic) и проставлять его при каждом `ToolCallStart`/`ToolCallArgsChunk`.

### 🟡 Minor / nit

- **`src/main/kotlin/com/faker/llm/adapter/openai/OpenAiRoutes.kt:31`** — неиспользуемый импорт `io.ktor.server.routing.RoutingContext`.
- **`src/main/kotlin/com/faker/llm/adapter/openai/OpenAiResponseMapper.kt:23`** — неиспользуемый импорт `kotlinx.coroutines.flow.collect` (стримим через `events.collect { ... }` extension, импорт остаточный).
- **`src/main/kotlin/com/faker/llm/adapter/anthropic/AnthropicRequestMapper.kt:3`** — неиспользуемый импорт `AnthropicMessage`.
- **`src/main/kotlin/com/faker/llm/adapter/openai/dto/Chunk.kt:25,42`** — дублирующая квалификация `kotlinx.serialization.ExperimentalSerializationApi` — рядом уже импортирован тот же класс (см. строку 4).
- **`src/main/kotlin/com/faker/llm/adapter/openai/OpenAiRoutes.kt:206` и `adapter/anthropic/AnthropicRoutes.kt:212`** — `@Suppress("UNUSED_PARAMETER")` на `requestId` помечен IDE как redundant. Логично или использовать `requestId` (см. ниже про request_id в SSE error frame), или убрать параметр.
- **`src/main/kotlin/com/faker/llm/Main.kt:41`** — redundant `@Suppress("unused")` (Ktor резолвит модуль рефлексией, но IDE его уже распознаёт). Не критично, оставлять можно.
- **`src/main/kotlin/com/faker/llm/Main.kt:50-51`, `adapter/openai/OpenAiRoutes.kt:52`, `adapter/anthropic/AnthropicRoutes.kt:51`** — magic string `"X-Request-Id"` дублируется в трёх местах; имя env-переменной `FAKER_REQUEST_ID_HEADER` тоже magic. Memory bank/контракт обязывают делать константы (`APPLIED_TIMING_HEADER` сделан). Завести `const val DEFAULT_REQUEST_ID_HEADER = "X-Request-Id"` (и аналогичный `ENV_REQUEST_ID_HEADER`) в `app/`.
- **`src/main/kotlin/com/faker/llm/adapter/openai/OpenAiResponseMapper.kt` (стрим path, строки 173-201) и `AnthropicResponseMapper.kt:354-365`** — в SSE mid-stream `event: error` фрейме `request_id` не прокидывается (его просто нет в сигнатуре `streamSse`). Контракт говорит «`request_id` в JSON body ТОЛЬКО для error-ответов». Mid-stream error технически — error response, и envelope тот же (`OpenAiErrorEnvelope`/`AnthropicErrorEnvelope`). Стоит явно решить и зафиксировать: либо прокидывать `requestId` в `streamSse` и эмитить, либо явно задокументировать «SSE in-stream error не имеет request_id». Сейчас поведение «случайно null» из-за того, что параметр просто не передан — это потенциально расхождение с контрактом.
- **`src/main/kotlin/com/faker/llm/adapter/anthropic/AnthropicRoutes.kt:115`** — для SyntheticHttpError на Anthropic: `type = decision.code ?: anthropicErrorTypeFor(decision.status)`. Memory bank говорит «Anthropic: error.type по статусу». Возможный кросс-флоу баг: если клиент передал `X-Faker-Directive` с `error.code = "foo"`, type на wire будет `"foo"` вместо `rate_limit_error`/`invalid_request_error`. Это либо by-design выпуск extensibility, либо отклонение. Решить и зафиксировать.
- **`src/main/kotlin/com/faker/llm/app/ErrorHandling.kt:69`** — `requestStartedNanos()` имеет fallback `System.nanoTime()`. Для StatusPages при сбое до route handler'а это даёт `faker_elapsed_ms ≈ 0`. Поведение задокументировано в комментарии — OK, но если когда-нибудь захочется отличать «реальные 0ms» от «не было anchor» — придётся возвращать sentinel.
- **`src/main/kotlin/com/faker/llm/adapter/openai/OpenAiResponseMapper.kt:198` и далее** — IDE кричит `BlockingMethodInNonBlockingContext` на `writer.write(...)`. `respondTextWriter` под капотом запускает writer в `Dispatchers.IO`-подобном контексте, поэтому в проде это OK. Если хочется убрать шум — можно `withContext(Dispatchers.IO)` или подавить инспекцию на функции. Не блокер.
- **`src/main/kotlin/com/faker/llm/adapter/openai/OpenAiRoutes.kt:184`** — в non-streaming success используется `OpenAiJson.json.encodeToString(...)` напрямую, минуя `json` локальную переменную (которая = тому же объекту). Инконсистентно с остальными местами — мелкая косметика.
- **`src/main/kotlin/com/faker/llm/adapter/anthropic/dto/ErrorBody.kt:15`** — `AnthropicErrorEnvelope.type = "error"` пишется и в SSE `event: error\ndata: { ... }` (см. `writeErrorEvent`). У реального Anthropic в data SSE-error frame'а top-level поле `type` остаётся `"error"` — соответствует, но стоит явно сверить с docs (memory bank это не покрывает).

### 🟢 Strengths

- `Main.kt:64-67` — два отдельных `Json` для адаптеров + общий `ktorJson` только для plug-in'ов. Чёткое разделение. Реальное соответствие memory bank `faker-llm-serialization-contract`.
- `markRequestStart()` стоит ПЕРВОЙ инструкцией в обоих post-роутах (`OpenAiRoutes.kt:57`, `AnthropicRoutes.kt:56`) — TTFT-anchor корректен.
- `EmitTimer(requestStartNanos)` создаётся локально в каждом вызове `streamSse` (`OpenAiResponseMapper.kt:152`, `AnthropicResponseMapper.kt:165`) — per-session, без shared state, как требует memory bank.
- `respondTextWriter(ContentType.Text.EventStream)` вместо `Route.sse` — соответствует ktor-wiring заметке. `Cache-Control: no-cache` + `Connection: keep-alive` ставятся ДО первого байта (`OpenAiRoutes.kt:241-243`, `AnthropicRoutes.kt:231-234`).
- `runCatching { json.decodeFromString(...) }` в адаптерах (`OpenAiRoutes.kt:62-66`, `AnthropicRoutes.kt:61-65`) — own handling вместо ContentNegotiation, как требовалось.
- `installFakerErrorHandling` правильно диспатчит OpenAI vs Anthropic по `request.path().startsWith("/v1/messages")` и emit'ит `X-Faker-Applied-Timing` + echo `X-Request-Id` header + `request_id` в JSON body.
- StatusPages обрабатывает `EmptyPoolException`, `SerializationException`, generic `Throwable` — три кита.
- Anthropic SSE: 6 событий все на месте (`message_start`, `content_block_start/delta/stop`, `message_delta`, `message_stop`), tool_use/thinking блоки с правильными discriminator'ами (`text_delta`/`thinking_delta`/`input_json_delta`), `MessageStartUsage.output_tokens = 1` совпадает с реальным API.
- TTFT semantics: Anthropic TTFT эмитируется на `content_block_start` через `EmitTimer.nextElapsedMs()` (первый delta после message_start), OpenAI — на первом chunk с `role: "assistant"` — соответствует memory bank.
- `handleSynthetic` для `timeout` корректно НЕ ставит `X-Faker-Applied-Timing` (соответствует контракту «эмитится на ВСЕХ путях кроме timeout»).
- `buildNonStreaming` явно игнорит `StreamError` (`OpenAiResponseMapper.kt:60`, `AnthropicResponseMapper.kt:84`) — это by-design fallback (HTTP 200 с обрезанным content), задокументированный в `faker-llm-known-limitations`. Реализация совпадает с документацией.
- DTO'шки: `@EncodeDefault(ALWAYS)` стоит на нужных литералах (`object`, `role`, `type`, `index`, OpenAI `code`/`param`, Anthropic `stop_sequence`). `explicitNulls` не выставлен — корректно по memory bank serialization-contract.

## Open questions для оркестратора

1. Mid-stream `event: error` фрейм должен ли echo'ить `request_id` в body? Если да — пробросить `requestId` в `streamSse` обоих адаптеров.
2. Anthropic SyntheticHttpError: `decision.code` сейчас перетирает status-derived `type`. Это by-design или баг? Memory bank явно требует mapping по статусу, но реализация даёт ручной override.
3. ErrorHandling.kt: окончательное решение по unified vs provider-specific `error.type` — рекомендую сделать provider-specific, текущие строки `invalid_request` / `internal_error` нестандартны для обоих провайдеров.
