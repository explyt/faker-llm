# Faker LLM — Task Execution Plan

## Your Mission

Реализовать Faker LLM на Kotlin + Ktor: HTTP-сервис, имитирующий поведение OpenAI (`/v1/chat/completions`) и Anthropic (`/v1/messages`) с правдоподобным таймингом (TTFT, inter-chunk delays), стримингом, tool calls, reasoning/thinking блоками и injection ошибок (HTTP 429/500/503/504/529 + mid-stream errors). Цель — нагрузочное тестирование инфры от 1000 RPS.

**Plan File:** `.tasks/faker-llm-tasks/PLAN.md`
**Tasks Directory:** `.tasks/faker-llm-tasks/`

## Execution Steps

### 1. Read This Plan
Просмотреть этот файл — найти следующую incomplete-задачу, key decisions, контекст от предыдущих агентов.

### 2. Understand Your Task
Прочитать соответствующий файл: `.tasks/faker-llm-tasks/task-XX-<name>.md`
- **Goal** — что нужно достичь
- **Key Points** — важные соображения
- **Done When** — объективные критерии приёмки

### 3. Execute the Task
- Сделать необходимые правки кода
- Проект должен компилироваться без ошибок (через `run_configuration` IDE-runner, NOT terminal `gradlew`)
- Все Done When-критерии должны быть удовлетворены

### 4. Update This Plan
- Отметить задачу как completed в `## Task Plan`
- Добавить 1-2 предложения outcome-summary в `## Shared Context`
- Зафиксировать только критичные решения, влияющие на следующие задачи

### 5. Await Approval (MANDATORY)
Дождаться подтверждения пользователя перед переходом к следующей задаче.

### 6. Review Task List (MANDATORY)
Проанализировать оставшиеся задачи на основе того, что узнал:
- Встретилась ли неожиданная сложность?
- Нужно ли разбить / объединить / удалить / переставить задачи?
- Не пропущена ли какая-то задача?

### 7. Present Review Findings (MANDATORY)
Всегда показать выводы — даже если изменений не нужно — и ждать approval.

### 8. Update Task Files (if approved)
- Изменить / создать task-файлы
- Обновить `## Task Plan` в этом PLAN.md

---

## Task Plan

- [x] `task-01-project-skeleton.md`: Project Skeleton — Gradle Kotlin DSL, Ktor 3.x, kotlinx.serialization, Logback, пакетная структура ✅
- [x] `task-02-core-domain-model.md`: Core Domain Model — `ResponsePart`, `PoolEntry` (Success / HttpError), `TimingProfile`, `RequestContext`, `AbstractStreamEvent`, error injection ✅
- [x] `task-03-pool-loader-selector.md`: Pool Loader & Selector — загрузка JSON из classpath, weighted pick + `RoutingDecision` filtering ✅
- [x] `task-04-request-router.md`: Request Router — `[[faker:force_id/tag/status:...]]` prompt directives через composite policy ✅
- [x] `task-05-default-pool-resources.md`: Default Pool Resources — JSON-файлы под short / medium / long / reasoning / mixed / tool_call / mid_stream_error / http_error ✅
- [x] `task-06-streaming-engine.md`: Streaming Engine — корутинный `Flow<AbstractStreamEvent>` с TTFT/inter-chunk delays, tool-name pick из request, mid-stream error injection ✅
- [x] `task-07-openai-adapter.md`: OpenAI Adapter — DTO + SSE-маппинг (`delta.content` / `delta.tool_calls` / `delta.reasoning` + `[DONE]`) ✅
- [x] `task-08-anthropic-adapter.md`: Anthropic Adapter — DTO + multi-event-type SSE (`message_start` → `content_block_*` → `message_delta` → `message_stop`) ✅
- [x] `task-09-ktor-application-wiring.md`: Ktor Application Wiring — Netty + минимальный `application.conf` + `StatusPages` + `/healthz` ✅
- [x] `task-10-manual-verification.md`: Manual Verification — 11 curl-сценариев под оба endpoint-а + cancellation ✅
- [x] `task-11-load-test-validation.md`: Load Test Validation — k6 + 1000 RPS + 60s + SLO-thresholds ✅ (SLO по latency не пройден, см. Task 12)
- [x] `task-12-load-anomaly-investigation.md`: Load Anomaly Investigation — анализ throughput → взяли **981 RPS / 0 dropped / 0 failures** (H1 подтверждена) ✅

---

## Shared Context

### Overview

Faker LLM мимикрирует под OpenAI и Anthropic API. Архитектура — hexagonal: провайдер-агностичное ядро (`domain` + `pool` + `routing` + `engine`) и адаптеры (`adapter/openai`, `adapter/anthropic`) сверху. Расширение под новый провайдер = новый адаптер без правок ядра.

Поведение определяется пулом JSON-entries (success / http_error) с весами. Выбор entry на запрос:
1. Адаптер парсит request → `RequestContext` (нормализованный view + `inspectableContent`)
2. `RequestRouter` инспектит `inspectableContent` на маркеры `[[faker:force_*]]` → `RoutingDecision`
3. `PoolSelector.pick(ctx, decision)` фильтрует и weighted-pick-ает entry
4. Если `SuccessEntry` → `StreamingEngine.execute(entry, ctx)` эмитит `Flow<AbstractStreamEvent>` с реалистичными задержками; адаптер маппит в свой SSE
5. Если `HttpErrorEntry` → адаптер возвращает HTTP-статус с провайдер-специфичным error-body (после `preResponseDelay`)

### Project Context

- **Stack**: Kotlin 2.x + Ktor 3.x + Netty + kotlinx.coroutines + kotlinx.serialization
- **JDK**: 21
- **Build**: Gradle Kotlin DSL + Shadow plugin (fat jar)
- **Targets**: 1000 RPS, 10k+ concurrent streams, p95 < 3s, p99 < 5s
- **Locked versions (Task 01)**: Kotlin `2.3.21`, Ktor `3.5.0` (+ `io.ktor.plugin`, used via `ktor-bom`), kotlinx-serialization-json `1.11.0`, kotlinx-coroutines `1.11.0`, logback-classic `1.5.34`, Shadow `com.gradleup.shadow 9.4.2`, Gradle wrapper `9.5.1`. Все версии вынесены в `gradle.properties`; правки версий — только там.
- **Ktor SSE**: `ktor-server-sse` присутствует в 3.5.0 — ручная `respondTextWriter`-запись как fallback не нужна (решено в Task 06/07/08).
- **Entrypoint**: `com.faker.llm.MainKt` (`Main.kt` сейчас stub-логгер; реальный Ktor-bootstrap — Task 09).

### Key Decisions

- **Без тестов в MVP**. Покрытие — через Manual Verification (Task 10) + Load Test (Task 11). Связь "сломается ли" контролируется compile-checks + curl-сценариями.
- **Без Docker**, **без DI-фреймворка**, **без своих метрик/Prometheus** в первой итерации. Метрики собирает клиент нагрузки (k6).
- **Без своего токенайзера**. `usage` стабится как `chars / 4`.
- **Без HOCON-секций для faker-логики**. Все поведенческие константы (директивы префиксы, пул-директория) — хардкод в коде. `application.conf` только под Ktor.
- **Pool entry выбор tool-имени**: имя НЕ хранится в entry; engine берёт случайное из `RequestContext.toolNames`. Применимо только когда `tools` в request непустой.
- **Forcing через prompt directives**: `[[faker:force_id:...]]`, `[[faker:force_tag:...]]`, `[[faker:force_status:...]]` внутри user/system content. Никаких HTTP-headers/URL-params — упрощает работу нагрузочного клиента.
- **Mid-stream errors** vs **HTTP errors** — разные сущности: первый прерывает успешный SSE, второй — полностью альтернативный ответ без стрима.
- **Архитектурный invariant**: пакет `domain` не импортирует ничего из `adapter/*` или `io.ktor.*`. Это контролирует, что добавление нового провайдера не требует правки ядра.

### Caveats & Problems

- **Reasoning в non-streaming OpenAI** — known limitation: формат `reasoning` для non-stream response не задокументирован OpenAI. В Task 07 решено: либо игнорим, либо обёрнем в `<think>...</think>`. Фиксируем по факту реализации.
- **`max_tokens` в Anthropic request** — обязателен по их спеке, но faker мягче (игнорим если нет). Документировать в README как deviation.
- **`anthropic-version` header не валидируется**. По дизайну.
- **Аутентификация полностью открыта**. Любой `Authorization` (или его отсутствие) принимается. По дизайну для нагрузочного фейкера.
- **Если `[[faker:force_id]]` указывает на несуществующий id** — `EmptyPoolException` → 500. Это feature: пусть клиент видит опечатку.
- **macOS Netty kqueue**: при проблемах на M-series — добавить `netty-transport-native-kqueue` в зависимости.
- **`ulimit -n`**: для 1000 RPS streaming перед нагрузочным тестом поднять `ulimit -n 65536`.
- **JDK toolchain auto-provision**: на машине только JDK 26 (Corretto), JDK 21 отсутствует. Добавлен `foojay-resolver-convention 1.0.0` в `settings.gradle.kts` → Gradle сам провиженит JDK 21 для `jvmToolchain(21)`. Без него toolchain resolution падает.
- **`.gitignore`**: дефолтный GitHub Java-шаблон содержал `*.jar` (зарубал бы `gradle-wrapper.jar`). Добавлена негация `!gradle/wrapper/gradle-wrapper.jar` + секции `.gradle/ build/ .idea/ *.iml`.
- **Task 01 outcome**: `build`, `run`, `shadowJar` зелёные через IDE-runner. Fat jar → `build/libs/faker-llm-all.jar`. Skeleton-пакеты держатся `.gitkeep`-файлами (удалить по мере наполнения).
- **Task 02 outcome**: 7 файлов ядра в `domain/` (`ResponsePart`, `PoolEntry`+`SuccessEntry`+`HttpErrorEntry`, `TimingProfile`+`RangeMs`/`RangeInt`, `Usage`+`FinishReason`, `ErrorModel`, `RequestContext`, `StreamEvent`). `.gitkeep` в `domain/` удалён. Round-trip 4 кейсов (text+thinking / tool-call / mid-stream AbruptDisconnect / http 429) — all OK, `Main.kt` возвращён к stub. Арх-invariant (0 импортов ktor/adapter в domain) проверен грепом.

#### Serialization contract (зафиксировано в Task 02, важно для Task 03/05)
- `PoolEntry` — polymorphic, дискриминатор `"kind"` (`@JsonClassDiscriminator`, требует `@OptIn(ExperimentalSerializationApi)`): `"success"` / `"http_error"`.
- `ResponsePart` — polymorphic, ДЕФОЛТНЫЙ дискриминатор `"type"`: `"text"` / `"thinking"` / `"tool_call"`. Два разных дискриминатора — чтобы не конфликтовали при вложении.
- Дефолты в JSON: `requiresTools=false`, `tag=null`, `finishReason=Stop`, `midStreamError=null`, `preResponseDelayMs={0,0}` — можно опускать в pool-JSON (Task 05).
- `AbstractStreamEvent` и `RequestContext` — РУНТАЙМ-only, НЕ `@Serializable`.
- `RangeMs/RangeInt.randomIn()` — ext-функции, вырожденный `min>=max` возвращает `min` (без исключения).
- Пакет `routing` создан в Task 03 (фронт-лоад, т.к. `PoolSelector.pick` принимает `RoutingDecision`). Сам `RequestRouter` (парсинг `[[faker:...]]`) — всё ещё Task 04.

- **Task 03 outcome**: `pool/` — `PoolJson` (shared `Json`), `PoolLoader`, `PoolSelector`, `EmptyPoolException`; `routing/RoutingDecision` (4 варианта: `Default`/`ForceEntryId`/`RequireTag`/`ForceHttpStatus`). Demo проверен и в `file:` (gradle run), и в `jar:` (`java -jar faker-llm-all.jar`) — обе ветки загрузчика идентичны. Demo-код/ресурсы удалены, `Main.kt` — stub.

#### Решения Task 03 (важны для Task 05 pool-JSON)
- **Глобальный `classDiscriminator` НЕ выставляется** в `PoolJson` (вопреки букве task-03). Причина: глобальный `"kind"` перекинул бы `ResponsePart` с его `"type"` и сломал контракт Task 02. Дискриминаторы — только через аннотации. `PoolJson` только `ignoreUnknownKeys = true`.
- **pool-JSON формат** (для Task 05): каждый entry — `{"kind":"success|http_error", ...}`, `parts[].type = text|thinking|tool_call`. Файл = либо один объект, либо массив. Дефолтные поля можно опускать.
- **Валидация при загрузке** (warn + skip, не падаем): `weight <= 0`; `SuccessEntry.requiresTools=true` без `ToolCall` в `parts`. Остальное (непарсибельный JSON) — тоже warn + skip всего файла.
- **Директория пула по дефолту** — `"pool"` на classpath (`src/main/resources/pool/`). Сейчас пуста (удалена после demo); наполняется в Task 05. Пустая/отсутствующая директория → warn + 0 entries (не краш).

- **Task 04 outcome**: `routing/` — `RequestRouter` (fun interface), `RoutingPolicy` (fun interface, `null`=pass-through), `CompositeRequestRouter` (первый non-null побеждает → иначе `Default`), `policies/PromptDirectivePolicy`. `RoutingDecision` был создан ещё в Task 03, совпал 1-в-1 — не переписывал. Demo: 8 сценариев зелёные, убран. `routing` без ktor/adapter-импортов (греп).

#### Решения Task 04 (важны для адаптеров Task 07/08)
- **`PromptDirectivePolicy` синтаксис**: `[[faker:<key>:<value>]]`, ключи `force_id`/`force_tag`/`force_status`. Префикс/суффикс — `private const` в самом файле (хардкод, без config).
- **Поиск маркера case-insensitive, но извлечённое значение СОХРАНЯЕТ регистр** (id/tag в пуле case-sensitive). Реализовано через `indexOf(ignoreCase=true)` по оригиналу (нет lowercase-haystack → нет Unicode index-drift).
- **При нескольких маркерах побеждает первый валидный по позиции**; невалидный/нераспознанный — skip и идём дальше; ни одного → `Default`.
- **Адаптеры (Task 07/08) обязаны** заполнять `RequestContext.inspectableContent` (concat user/system контента), иначе роутер всегда вернёт `Default`.

- **Task 05 outcome**: `src/main/resources/pool/` — 8 файлов (`01-short`..`08-http-errors`), **26 entries**, загружаются `0 invalid skipped`. Распределение весов точно в таргет: short/medium/long=70% (25/25/20), reasoning+mixed=10%, tool_call=10%, mid_stream_error=5%, http_error=5%. Теги: `short/medium/long/reasoning/mixed/tool_call/mid_stream_error/http_error`. http-статусы 429/500/503/504/529 (по 1 entry). Все 26 id уникальны, читаемые (для `force_id`). Часть контента на RU.
- Контент mid_stream_error entries специально длинный (чтобы хватило на `afterChunks` 5/10/3 до обрыва). Engine (Task 06) будет резать по `chunkSizeChars` 2-8.

- **Task 06 outcome**: `engine/` — `StreamingEngine` (interface), `DefaultStreamingEngine` (cold `flow {}` + suspend `delay`), `Chunking.chunkByRange` (lazy `Sequence`), `CallIdGenerator` (`call_<24 alnum>`). Добавлены overloads `RangeMs/RangeInt.randomIn(random)` в domain. Demo проверил 5 инвариантов: TTFT/inter-chunk тайминг (первый text-chunk на +173ms, delta между чанками ≈ 25-26ms), tool-call Start→args→End (имя из ctx, callId match, JSON reassemble), mid-stream error без StreamEnd, `HttpErrorEntry`→IAE, cancellation через `cancelAndJoin`. `engine` без ktor/adapter-импортов.

#### Решения Task 06 (важны для адаптеров Task 07/08)
- **Mid-stream error counter** — считаются ТОЛЬКО content-чанки (`TextChunk`/`ThinkingChunk`/`ToolCallArgsChunk`). `StreamStart`/`ToolCallStart`/`ToolCallEnd` — структурные, не считаются.
- **`afterChunks <= 0`** — эррор файрится ПЕРЕД первым чанком (отдельный pre-check). Кроме того — post-emit check после каждого content-чанка.
- **При mid-stream внутри tool-call** — `ToolCallEnd` НЕ эмитится. Адаптеры должны обработать `StreamError` как финальное событие (нет `[DONE]`/`message_stop`).
- **`StreamError.body`** — синтетический: `type="stream_error"`, `message="Injected mid-stream error: <kind>"`. Адаптер мапит в свой формат.
- **Tool args сериализация** — `JsonObject.toString()` (compact JSON по контракту kotlinx). Engine НЕ зависит от `PoolJson`.
- **Нет токенайзера**: `UsageStub.promptChars = inspectableContent?.length ?: 0`, `completionChars` — сумма `delta.length` всех проэмиченных content-чанков. Адаптер делит на 4 (по plan caveat).

- **Task 07 outcome**: `adapter/openai/` — 4 DTO-файла (`Request`/`Response`/`Chunk`/`ErrorBody`), `OpenAiJson`, `OpenAiRequestMapper`, `OpenAiResponseMapper`, `OpenAiRoutes`. Поднял in-process Netty (свободный порт) — 6 сценариев все зелёные: non-stream (200, ChatCompletionResponse-shape), stream short (14 фреймов в [DONE]), stream tool (5 фреймов: первый с id+type+name, второй с arguments, finish_reason=tool_calls), stream reasoning (144 фрейма, 124 reasoning-delta), http 429 (error.{message,type,code:null,param:null}), mid-stream ErrorEvent (event:error фрейм, НЕТ [DONE]).

#### Решения Task 07 (важны для Task 08/09)
- **`receiveText()` + ручная десериализация и `respondText()` + ручная сериализация** — адаптер НЕ зависит от `ContentNegotiation`-плагина. Self-contained, в Task 09 плагин можно не вешать.
- **`OpenAiJson` конфиг**: `encodeDefaults = false` + точечный `@EncodeDefault(ALWAYS)` в DTO-полях которые должны всегда писаться (`object`, `index`, `role=assistant`, `type=function`, error `code`/`param`). `explicitNulls = false` ИСПОЛЬЗОВАТЬ НЕЛЬЗЯ — он перебивает аннотацию и нули в их поля не попадают (real OpenAI возвращает `code:null,param:null`).
- **Reasoning в non-streaming**: выбрал `<think>...</think>` обёртку перед основным content (plan-вариант “либо игнор, либо <think>”).
- **`OpenAiResponseMapper`** — stateless класс с инжектируемыми `random` (для `chatcmpl-` id) и `nowEpochSec`. id/created генерятся раз на stream/response, между фреймами совпадают (видно в логах: `chatcmpl-Cpgb7…` повторяется в 3 фреймах).
- **При mid-stream-ошибке** `MalformedJson` эмитится буквально `data: {\n\n` и close — я намеренно не экранирую (цель — сломать парсер клиента).
- **SSE plugin из ktor-server-sse НЕ используется**: в 3.5.0 `Route.sse(...)` — GET-only, без контроля над raw-фреймами (нужен для `event: error`). Идём через `respondTextWriter(ContentType.Text.EventStream)`. Зависимость `ktor-server-sse` в build.gradle.kts можно убрать в Task 09 — Антропик-адаптер тоже ходит через `respondTextWriter`, plugin нигде не используется.

- **Task 08 outcome**: `adapter/anthropic/` — 5 DTO-файлов (`Request`/`Response`/`StreamEvents`/`ErrorBody`), `AnthropicJson`, `AnthropicRequestMapper`, `AnthropicResponseMapper` (stateful streaming с внутренним `StreamState`), `AnthropicRoutes`. 7 сценариев in-process все зелёные: non-stream short, stream short, stream thinking, stream **mixed** (4 блока с indices=[0,1,2,3] types=[thinking,text,thinking,text]), stream tool_use (пустой `input:{}` при старте + input_json_delta), http 529 (overloaded_error), mid-stream ErrorEvent (НЕТ message_stop).

#### Решения Task 08 (важны для Task 09)
- **Stateful streaming с внутренним `StreamState`** (внутри одного запроса): трекает `currentIndex` и `openType` (Text/Thinking/ToolUse). При смене типа блока — `content_block_stop` предыдущего + `content_block_start` нового (index++). При mid-stream-ошибке блок НЕ закрывается (по Task 06 контракту).
- **`@EncodeDefault(ALWAYS)` паттерн** (из Task 07) работает и здесь: литеральные `type` (`"message_start"`, `"content_block_delta"` и т.д.), внешний `type:"error"`, `stop_sequence:null`, `output_tokens:1` в `message_start.usage` — все пишутся явно.
- **Два polymorphic sealed (`AnthropicContentBlock`, `ContentBlockDelta`)** — оба используют default kotlinx-дискриминатор `"type"`, никаких оверрайдов в `AnthropicJson`. Sealed-варианты регистрируются автоматически.
- **`anthropic-version` заголовок не валидируется** (по дизайну).
- **Non-streaming `input` в tool_use**: собирается из конкатенации `ToolCallArgsChunk.delta`, парсится `parseToJsonElement`. Если не JSON-объект — fallback `{"raw":"<string>"}`.
- **Task 09 готов**: оба адаптера self-contained (без `ContentNegotiation`-плагина), подключаются в `routing {}` одной строкой каждый: `openAiRoutes(selector, router, engine)` / `anthropicRoutes(selector, router, engine)`.

- **Task 09 outcome**: `Main.kt` = `EngineMain.main(args)` + `Application.module()`, `app/HealthRoute.kt`, `app/ErrorHandling.kt`. `application.conf` — минимальный (port 8080 / $PORT оверрайд, modules → `MainKt.module`). Проверено живым сервером на `:18080` (PORT env): healthz=200, /v1/chat/completions+/v1/messages — 200, EmptyPoolException → 500 pool_misconfigured (в форме обоих провайдеров, dispatch по пути), invalid JSON → 400 invalid_request_error, mid-stream отмена клиентом (`curl --max-time 1`) — чистый выход без зависших корутин. CallLogging: строки вида `200 OK: POST - /v1/chat/completions in 928ms`, body в логах = 0 совпадений.

#### Решения Task 09 (важны для Task 10/11)
- **`PoolJson` НЕ переиспользуется в `ContentNegotiation`** (вопреки букве task-09). `PoolJson` заточен под чтение пула (`ignoreUnknownKeys` без `encodeDefaults=false`), это бы сломало wire-формат ошибок (`code:null`/`stop_sequence:null` пропали бы). Сделал отдельный `ktorJson` в `Application.module()`: `ignoreUnknownKeys=true, encodeDefaults=false` — в паре с `@EncodeDefault(ALWAYS)` на DTO-полях.
- **`StatusPages` — глобальный handler, формат ошибки dispatch-ится по пути**: `/v1/messages` → Anthropic-envelope, иначе → OpenAI-envelope. Это в `app/ErrorHandling.kt::installFakerErrorHandling`. Адаптеры свои `runCatching` на парсинге держат — их SerializationException-путь в StatusPages это fallback для будущих маршрутов.
- **Для Task 10**: curl-примеры уже обкатаны на `:18080`. Сервер стартует за ~1.7с (`Application started in 1.705 seconds`).
- **`log` extension Application в Ktor 3.5.0 ОТСУТСТВУЕТ** (был в старых версиях). Использую top-level `LoggerFactory.getLogger("com.faker.llm.module")`.

- **Task 10 outcome**: все 11 curl-сценариев прогнаны против `gradle run` на `:8080`. Итоговая таблица ниже; `docs/manual-checks.md` — snapshot для повторных прогонов.

| # | Сценарий | Результат | Ключевые выводы |
|---|---|---|---|
| 10.1 | OpenAI non-stream | **OK** | `HTTP=200`, `chatcmpl-eCgD4…`, `object:"chat.completion"`, валидные choices/usage |
| 10.2 | OpenAI stream | **OK** | `frames=159`, финал `data: [DONE]` |
| 10.3 | OpenAI tool call (forced) | **OK** | Первый tool-фрейм с `id+type+function.name=get_weather`, `arguments` в отдельном, `finish_reason:"tool_calls"` |
| 10.4 | OpenAI force 429 | **OK** | `HTTP/1.1 429 Too Many Requests`, `type:"rate_limit_error"`, `code:null, param:null` |
| 10.5 | OpenAI force_id несуществующий | **OK** | `HTTP/1.1 500`, `type:"pool_misconfigured"`, message с деталями decision и счётчиками пула |
| 10.6 | Anthropic non-stream | **OK** | `msg_RxfnMd…`, `type:"message"`, `content[].type:"text"`, `stop_reason:"end_turn"`, `stop_sequence:null` явно |
| 10.7 | Anthropic stream | **OK** | 65 событий, порядок `message_start → content_block_start → …delta… → content_block_stop → message_delta → message_stop` |
| 10.8 | Anthropic thinking | **OK** | 2 блока: `type=thinking` (103 thinking_delta), `type=text` (12 text_delta) |
| 10.9 | Anthropic tool_use | **OK** | `content_block_start` с `type:tool_use, id:call_…, name:get_weather, input:{}`, 10 input_json_delta, `stop_reason:"tool_use"` |
| 10.10 | Mid-stream error (3 runs) | **OK** | run1: 12 фреймов + `event: error` (ErrorEvent), run2/run3: обрыв без [DONE] (AbruptDisconnect/MalformedJson). Ни в одном нет `[DONE]` |
| 10.11 | Connection cancellation | **OK** | curl --max-time 1 оборвал после 1 события, jstack → нет hung Flow/coroutine стеков, healthz после = ok |

#### Known limitation (выявлена в 10.6)
- **Mid-stream error в non-streaming режиме**: если weighted pick выбрал mid_stream_error entry в non-stream-запросе, клиент получит `HTTP 200` с обрезанным content (в 10.6 видно: `"Sure, let me start exp"` — обрыв по слову из `mid-abrupt-disconnect-01`). Причина: `buildNonStreaming` собирает events и игнорит `StreamError`, формируя финальный объект из собранных chunks. Семантически mid-stream error задуман под streaming — в non-stream-пути это просто "срез" событий. Для явных 5xx в non-stream — `force_status:5xx`. Не блокер, задокументировано в `docs/manual-checks.md`.

- **Task 11 outcome (два прогона)**: `loadtest/faker-load.js` + `loadtest/README.md`. Артефакт кода: env-override `FAKER_POOL_DIR` в `Main.kt` + classpath-директория `pool-clean/` (6 файлов, 18 success entries, без http_error и mid_stream_error).

| Метрика | Прогон 1 (full pool) | Прогон 2 (clean pool) | SLO | Вердикт |
|---|---|---|---|---|
| Throughput | 739 RPS | **794 RPS** | 1000 RPS | ❌ |
| dropped_iterations | 5935 | **7067** | 0 | ❌ |
| http_req_failed | 5.26% | **0.00%** (0/52936) | <2% | ✅ |
| p95 latency | 5.93s | **5.9s** | <3s | ❌ |
| p99 latency | 7.08s | **6.43s** | <5s | ❌ |
| max latency | 14.98s | 7.56s | — | — |
| Thread count (mid-test) | 1106 | 1041 | — | — |
| RSS (mid-test) | 412MB | 415MB | — | — |

**Выводы из чистого прогона**:
- **Faker функционально безупречен**: 0 failures из 52936 запросов, все 3 типа (streaming/non-streaming/tool-calls) отвечают 200.
- **Throughput 794/1000 RPS** + 7k dropped iterations — реальный bottleneck на стороне сервера (не генератор — max VUs не выбраны: 2530/2550).
- **p95=5.9s — следствие дизайна пула**: `long-*` entries имеют `ttftMs.max=1500ms`, ~700 символов / `chunkSize=2-8` → ~150 чанков × `interChunk=10-50ms` = 5-7с на стрим. `reasoning-*` хуже (TTFT до 3с). **Спецификации task-05 и task-11 противоречат друг другу**: пул запроектирован под p95~6s, SLO требует <3s.
- **5.26% failures в прогоне 1 — by design** (5% http_error + ~5% mid_stream_error), не баг. План task-11 явно это упоминает как Gotcha.

#### Решения Task 11
- **Создана Task 12 (Load Anomaly Investigation)** для разбора throughput 794 RPS + p95 5.9s. Акцент: async-profiler / JFR / GC log, не блиндные правки кода. План task-11 явно предписывает это при невыдержанных SLO.
- **`FAKER_POOL_DIR` env-override остаётся в прод-коде** — полезный hook для любого профилирования / a/b-тестирования пулов, оверхеда 0.

- **Task 12 outcome — RPS взят**:

| Stage | Pool | maxVUs (stream) | scheduler | **RPS** | dropped | failures | p95 |
|---|---|---|---|---|---|---|---|
| 1 | full | 2000 | 64 | 739 | 5935 | 5.26% | 5.93s |
| 2 | clean | 2000 | 64 | 794 | 7067 | 0.00% | 5.9s |
| 3 | clean | 6000 | 256 | 880 | 1551 | 0.00% | 5.89s |
| **4** | **short-only** | **6000** | **256** | **981** | **0** | **0.00%** | **1.08s** |

**Stage 4 — все SLO зелёные**: `http_reqs=60002 (981.3 RPS) / 0 out of 60002 failed / p95=1.08s / p99=1.17s`. `vus.max=760` при `vus_max=1400` — сервер НЕ выбрал VU pool, есть запас.

**Диагноз — H1 подтверждена**: bottleneck был в дизайне пула, не в коде. `long-*` (TTFT 200-1500ms + ~700 chars / chunkSize 2-8 / interChunk 10-50ms) и `reasoning-*` (TTFT 500-3000ms) entries порождают стримы 5-7с; при 700 stream/s нужно ~4200 одновременных соединений, сервер не вытянул. H2 (scheduler.max.pool.size 64→256) дала “бесплатные” +11% (794→880). H1 (короткие entries) дала +11% сверху и сняла потолок целиком. H3 (Netty kqueue) и H4 (GC) не проверялись — не потребовались.

**Артефакты Task 12**: `loadtest/faker-load-stage2.js` (большие VU pools для высокой нагрузки), `src/main/resources/pool-short-only/` (2 файла, 6 entries: short-replies + tool-calls). Оба пула-overlay (`pool-clean`, `pool-short-only`) остаются в resources — доступны через `FAKER_POOL_DIR`.

**Рекомендации**:
- Для прод-запуска на 1000+ RPS: `FAKER_POOL_DIR=pool-short-only` + `-Dkotlinx.coroutines.scheduler.max.pool.size=256` + `ulimit -n 65536`. Даст 981 RPS / p99 в секунде.
- Для "реалистического" фейка (дефолтный `pool/`) — планировать на ~880 RPS пика на M-series; для 1000+ нужно больше ядер / хоризонтальный scale.
