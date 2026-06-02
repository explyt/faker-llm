# Docs + Contract Conformance Review

## Summary

`faker-contract.md` реализован на ~85%: все 8 `type`, X-Faker-Applied-Timing на всех путях кроме timeout, X-Request-Id echo (header + body для ошибок) — всё работает по коду. Но **сам README не знает о существовании контракта**: ни одно упоминание `X-Faker-Directive`, `X-Faker-Applied-Timing`, `X-Request-Id`, `FAKER_REQUEST_ID_HEADER` или nonstandard-поля `faker_elapsed_ms` в нём нет. Это критично, потому что внешняя нагрузочная команда будет читать именно README. `docs/manual-checks.md` тоже не проверяет ни одного directive-сценария. Отклонения от спеки (`tokens.output`/`seed`/`total_ms` cap игнорируются) нигде не задокументированы — клиент узнает об этом, только подняв вопрос «а почему мой `seed` ничего не меняет».

## Findings

### 🔴 Critical

- **README.md (отсутствует целая секция «X-Faker-Directive»)** — главный пункт контракта. По коду заголовок реализован (`HeaderDirectivePolicy.kt:71` + 8 типов в `SyntheticEntryBuilder.kt`), но в README нет ни одной строчки про него. Внешняя команда нагрузочного тестирования не сможет включить режим `faker` без чтения исходников. Фикс: добавить секцию «X-Faker-Directive (load-test contract)» со списком всех 8 типов, схемой JSON и хотя бы одним curl-примером каждой ветки.
- **README.md (отсутствует «X-Faker-Applied-Timing»)** — контракт явно говорит «эхо delay обязательно в режиме faker». Реализовано в `app/AppliedTiming.kt` + эмитится во всех роутах. В README — 0 упоминаний, в env vars нет связанной переменной. Фикс: задокументировать формат `{"ttft_ms":N,"itl_ms":N,"total_ms":N}` и явно прописать, на каких типах он выставляется (всё кроме `timeout`).
- **README.md (отсутствует «X-Request-Id»)** — нагрузочная команда: «request-id критично, проверяется под нагрузкой». В коде: `FAKER_REQUEST_ID_HEADER` env (Main.kt:50), echo в header для всех + в body для ошибок (ErrorHandling.kt:73, OpenAiErrorEnvelope/AnthropicErrorEnvelope.request_id). В README: 0 упоминаний; в Env vars-таблице — отсутствует. Фикс: дописать строку в табличку env-vars + параграф про echo-поведение.
- **README.md / docs/manual-checks.md (поле `faker_elapsed_ms` не задокументировано)** — это **намеренное отклонение от OpenAI/Anthropic спек** (memory `faker-llm-known-limitations` явно требует документировать). В wire оно required во всех response/SSE/error envelope (`dto/Chunk.kt:22`, `dto/Response.kt:18`, `dto/ErrorBody.kt:16` и 7 anthropic точек). Строгие клиенты с schema validation на это упадут. Фикс: добавить в README/«Known limitations» предупреждение «faker_elapsed_ms — non-standard required field во всех ответах».

### 🟠 Major

- **docs/manual-checks.md (нет покрытия X-Faker-Directive)** — 11 сценариев манчеков покрывают только `[[faker:...]]` маркеры (старый внутренний механизм). Внешний контракт сейчас полностью без manual-чеков. Фикс: добавить 8 сценариев под `X-Faker-Directive` (по одному на каждый `type`).
- **docs/manual-checks.md (нет проверки Applied-Timing / X-Request-Id)** — ни одна команда не использует `-i` для просмотра response-headers и не отправляет `X-Request-Id`. Под нагрузкой команда проверяет «echo именно отправленного id» — сейчас они не смогут это сверить руками. Фикс: добавить раздел «Cross-cutting → 10.X X-Faker-Applied-Timing» и «10.Y X-Request-Id echo (header + body для ошибок)».
- **README.md Env vars (неполная таблица)** — отсутствует `FAKER_REQUEST_ID_HEADER` (реально читается в `Main.kt:50`) и `LOG_DIR` (используется в `logback.xml` через `-DLOG_DIR=...`). Фикс: дописать обе строки.
- **README.md Known limitations (неполный)** — реализация явно игнорирует `tokens.output`, `seed`, `timing.total_ms` cap (memory + код подтверждают). В контракте поля заявлены как опциональные, но клиент должен знать, что они **молча игнорируются**. Также не упомянуто, что Per-frame Applied-Timing в SSE отсутствует (только один header перед body). Фикс: дописать секцию.
- **README.md «Pool overlays» (пропущен `pool-deepseek`)** — таблица перечисляет только `pool` / `pool-clean` / `pool-short-only`, но `src/main/resources/pool-deepseek/` физически существует (10 файлов, упомянут в memory `faker-llm-load-tuning`). Либо документировать, либо явно указать «experimental, не для load-test».

### 🟡 Minor / nit

- **README.md «Scripts» (пропущен `scripts/docker.sh`)** — он существует и обёртывает `docker compose up/down/smoke`. Раз он есть в репо — должен быть в таблице.
- **README.md (нет упоминания Docker)** — `Dockerfile` + `docker-compose.yml` + `scripts/docker.sh` есть, но в README — 0 упоминаний. Внешний клиент захочет поднять faker в контейнере.
- **docs/manual-checks.md:5 (`gradle run`)** — README везде использует fat jar (`./scripts/run.sh`/`./scripts/run-background.sh`). Manual-checks предлагает `gradle run`. Разнобой. Фикс: привести к одному способу (или сразу к двум с явной пометкой).
- **docs/manual-checks.md (нет упоминания `max_tokens` not required)** — non-stream Anthropic пример в 10.6 шлёт `max_tokens:1024`, но реальный Anthropic его требует, а faker — нет (memory + код подтверждают). Если убрать — будет работать; стоит написать одной строкой.
- **README.md TL;DR (двусмысленность про логи)** — в TL;DR упомянуто «daemon (логи в .run/faker.log)», а `scripts/logs.sh` тейлит `logs/faker-llm.log` (logback rolling appender). Две разные дорожки логов реально существуют (stdout-redirect в `run-background.sh:18` + logback file в `logback.xml`). Запутывает.
- **README.md «Нагрузочное тестирование» (STAGE)** — `scripts/loadtest.sh` поддерживает только `STAGE=1|2`, но в `loadtest/` лежат `faker-load-stage3.js`, `stage4.js`, `stage4b.js`, `stage5.js`. README про них не говорит, скрипт их не вызывает. Out of scope ops-ревьюера, но фиксирую для координации.
- **README.md:46 «Prompt directives»** — `[[faker:force_status:<n>]]` помечен как «если есть в пуле». На самом деле `force_status` фильтрует пул по `HttpErrorEntry.status`; если такого статуса нет — `EmptyPoolException` → 500. Это поведение задокументировано в `Known limitations` (через `force_id` строку), но `force_status` отдельно — нет. Полезно дописать.

### 🟢 Strengths

- `faker-contract.md` сам по себе — внешний документ, нашего ревью не требует, но реализация ему **соответствует на 85% по букве** (см. матрицу ниже).
- TL;DR в README сделан реально как quick start: 6 команд, рабочая последовательность.
- curl-примеры в `docs/manual-checks.md` проверены на соответствие реальному коду (типы `chat.completion`, `rate_limit_error`, `pool_misconfigured` — всё совпадает).
- `docs/architecture.svg` существует, на него есть ссылки в README (строка 33 + 178).
- `Known limitations` в README покрывает 5 by-design quirks (mid-stream non-stream, anthropic-version, auth, force_id miss, reasoning non-stream wrap).

## Contract Conformance Matrix

| Контракт-пункт | Статус | Где | Комментарий |
|---|---|---|---|
| `X-Faker-Directive` JSON parsing | ✅ | `HeaderDirectivePolicy.kt:36-41` | tolerant: malformed JSON → WARN + pass-through |
| `type=normal` | ✅ | `HeaderDirectivePolicy.kt:67` (else branch) | Pass-through к pool |
| `type=error` | ✅ | `HeaderDirectivePolicy.kt:48-53` | `SyntheticHttpError(status,code,message)` |
| `type=thinking` | ✅ | `SyntheticEntryBuilder.kt:52-68` | `Thinking(min_tokens×4 chars) + Text("Done.")` |
| `type=tool_call` | ✅ | `SyntheticEntryBuilder.kt:70-82` + `overrideContext` | `overrideContext.toolNames=[directive.name]` |
| `type=slow` | ✅ | `SyntheticEntryBuilder.kt:45-50`, `timingFromDirective:96-104` | Использует `timing.ttft_ms`/`itl_ms` |
| `type=timeout` | ✅ | `OpenAiRoutes.kt:214-218`, `AnthropicRoutes.kt:220-224` | `delay(Long.MAX_VALUE)`, **без** Applied-Timing header |
| `type=rate_limit` | ✅ | `HeaderDirectivePolicy.kt:55-62` | HTTP 429 с defaults `rate_limit_exceeded`/`Rate limit exceeded` |
| `type=empty` | ✅ | `SyntheticEntryBuilder.kt:39-44` | `SuccessEntry(parts=emptyList())` |
| `error.http_status` | ✅ | `FakerDirective.kt:32` → `HeaderDirectivePolicy.kt:50` | Default 500 если нет |
| `error.code` | ✅ | OpenAI `error.code` / Anthropic → `error.type` | `OpenAiRoutes.kt:117`, `AnthropicRoutes.kt:117` |
| `error.message` | ✅ | `FakerDirective.kt:34` | Default "Faker injected error" |
| `thinking.min_tokens` | ✅ | `FakerDirective.kt:40`, default 20 → ~80 chars | 4 chars/token approx |
| `tool_call.name` | ✅ | `FakerDirective.kt:49`, default "fake_tool" | Через `overrideContext.toolNames` |
| `tool_call.args_keys` | ✅ | `FakerDirective.kt:50`, default `["arg"]` | JSON `{"key":"placeholder_<key>"}` |
| `tokens.output` | ❌ | — | Не реализовано. **Не задокументировано** в README. |
| `timing.ttft_ms` | ✅ | `SyntheticEntryBuilder.kt:97` | Только для `slow` |
| `timing.itl_ms` | ✅ | `SyntheticEntryBuilder.kt:98` | Только для `slow` |
| `timing.total_ms` cap | ⚠️ | `FakerDirective.kt:58` (поле парсится) | Парсится, но **не enforced**: faker не лимитирует сверху. Не задокументировано. |
| `seed` | ❌ | Нет в `FakerDirective.kt` | Не реализовано. **Не задокументировано.** |
| `X-Faker-Applied-Timing` формат | ✅ | `AppliedTiming.kt:8-25` | `{"ttft_ms":N,"itl_ms":N,"total_ms":N}` |
| Applied-Timing на `normal` | ✅ | non-streaming: `OpenAiRoutes.kt:182`, streaming: `estimateForStreaming` | wall-clock / estimate |
| Applied-Timing на `slow` | ✅ | `streamSuccess` через `estimateForStreaming(entry)` | avg(ttft/itl) |
| Applied-Timing на `thinking` | ✅ | то же | |
| Applied-Timing на `tool_call` | ✅ | то же | |
| Applied-Timing на `empty` | ✅ | `AppliedTiming.kt:65` (collapse to `ttft_ms`) | totalChars=0 → total_ms=ttft |
| Applied-Timing на `error` | ✅ | `OpenAiRoutes.kt:108`, `AnthropicRoutes.kt:108` | `fromElapsed(0, elapsedMs)` |
| Applied-Timing на `rate_limit` | ✅ | то же (один путь через `respondSyntheticError`) | |
| Applied-Timing на `timeout` | ✅ | `OpenAiRoutes.kt:214-218` — header не выставляется | По контракту «нет» |
| Per-frame Applied-Timing в SSE | ⚪ | — | Out of scope (команда faker решила: один header перед body) |
| Имя request-id настраивается | ✅ | `Main.kt:50` `FAKER_REQUEST_ID_HEADER` env | |
| Default `X-Request-Id` | ✅ | `Main.kt:51` | |
| request-id echo в **header** (всегда) | ✅ | `OpenAiRoutes.kt:60`, `AnthropicRoutes.kt:59`, `ErrorHandling.kt:73` | Только если клиент прислал |
| request-id echo в **body** для error | ✅ | `OpenAiErrorEnvelope.request_id:18`, `AnthropicErrorEnvelope.request_id:20` | nullable, omitted when null |

### Итог матрицы

- ✅ реализовано: **30**
- ⚠️ частично: **1** (timing.total_ms cap не enforced)
- ❌ не реализовано: **2** (tokens.output, seed)
- ⚪ out of scope: **1** (per-frame Applied-Timing)

## Open questions для оркестратора

- `tokens.output` / `seed` — реально нужны контракту нагрузочной команды или это «hide it from clients» молча? Если нужны хотя бы как fail-loud reject — это уже не doc-only фикс, а код. Я только зафиксировал «не реализовано + не задокументировано».
- `timing.total_ms` cap — клиент может рассчитывать, что faker сам обрежет ответ если генерация займёт дольше указанного `total_ms`. Сейчас он не обрежет. Нужно либо реализовать, либо явно прописать в README «total_ms — это hint, не cap».
- `pool-deepseek` overlay в `src/main/resources` — это experimental или production overlay? Если первое — может стоит вынести в отдельную ветку/каталог, чтобы не путать docs.
