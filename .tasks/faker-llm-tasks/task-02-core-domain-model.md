# Task 02: Core Domain Model

**Type:** Code Modification

## Goal

Описать провайдер-агностичные доменные типы: pool entry, response parts, timing profile, абстрактные стрим-события и встроенный механизм error injection (pre-stream и mid-stream). Это контракт между ядром (pool + engine) и адаптерами (OpenAI / Anthropic). После этой задачи добавление нового адаптера или нового типа ошибки не требует переделки ядра.

## What to Do

### Response parts (успешные)

- В пакете `com.faker.llm.domain` создать sealed-иерархию `ResponsePart`:
  - `data class Text(val content: String) : ResponsePart`
  - `data class Thinking(val content: String) : ResponsePart`
  - `data class ToolCall(val argsTemplate: JsonObject) : ResponsePart`
- Имя tool-а не хранится в domain. В этой итерации engine (Task 06) выбирает случайный инструмент из `RequestContext.toolNames` (при пустом списке эта entry уже отфильтрована selector-ом через `requiresTools`).

### Timing и usage

- `data class TimingProfile(val ttftMs: RangeMs, val interChunkMs: RangeMs, val chunkSizeChars: RangeInt)`
- Surrogate-типы: `data class RangeMs(val min: Long, val max: Long)`, `data class RangeInt(val min: Int, val max: Int)` + extension-функции `randomIn(): Long/Int`
- `data class UsageStub(val promptChars: Int, val completionChars: Int)` — сырые длины; адаптер сам маппит в `prompt_tokens` / `input_tokens`
- `enum class FinishReason { Stop, Length, ToolCalls, Error }`

### Pool entry (sealed — success либо error)

- `sealed interface PoolEntry { val id: String; val weight: Double; val requiresTools: Boolean; val tag: String? }`
  - `tag` — опциональный ярлык для категорийного форсинга через `RoutingDecision.RequireTag` (например `"tool_call"`, `"long_reasoning"`, `"http_error"`)
  - `requiresTools = true` означает, что entry применимо только если в request есть непустые `tools`
- `data class SuccessEntry(...) : PoolEntry`:
  - `parts: List<ResponsePart>`
  - `timing: TimingProfile`
  - `finishReason: FinishReason` (по умолчанию `Stop`; для tool-call-entry — `ToolCalls`)
  - `midStreamError: MidStreamError? = null` — опциональный сбой посреди стрима
- `data class HttpErrorEntry(...) : PoolEntry`:
  - `status: Int` (429, 500, 502, 503, 504, 529 и т.п.)
  - `errorBody: ErrorBody`
  - `preResponseDelayMs: RangeMs = RangeMs(0, 0)` — задержка перед отдачей ошибки

### Error injection

- `data class ErrorBody(val type: String, val message: String)` — провайдер-агностичная пара; адаптер мапит в свой формат
- `data class MidStreamError(val afterChunks: Int, val kind: MidStreamErrorKind)`
- `enum class MidStreamErrorKind`:
  - `AbruptDisconnect` — закрыть соединение без финального события
  - `ErrorEvent` — отправить SSE-событие `event: error` с телом
  - `MalformedJson` — отправить сломанный фрейм и закрыть

### Request context

- `data class RequestContext(val hasTools: Boolean, val toolNames: List<String>, val stream: Boolean, val model: String?, val inspectableContent: String?)` — нормализованный view; адаптеры заполняют, ядро читает
- `inspectableContent` — конкат user-message контента (адаптер собирает провайдер-специфично из `messages[]`). Используется `RequestRouter` (Task 04) для поиска маркеров `[[faker:...]]`. `null` допустим для эндпоинтов без user-месседжей.

### Stream events (абстрактные)

- `sealed interface AbstractStreamEvent`:
  - `data object StreamStart : AbstractStreamEvent`
  - `data class TextChunk(val delta: String) : AbstractStreamEvent`
  - `data class ThinkingChunk(val delta: String) : AbstractStreamEvent`
  - `data class ToolCallStart(val toolName: String, val callId: String) : AbstractStreamEvent`
  - `data class ToolCallArgsChunk(val delta: String) : AbstractStreamEvent`
  - `data class ToolCallEnd(val callId: String) : AbstractStreamEvent`
  - `data class StreamError(val kind: MidStreamErrorKind, val body: ErrorBody) : AbstractStreamEvent`
  - `data class StreamEnd(val finishReason: FinishReason, val usage: UsageStub) : AbstractStreamEvent`

### Сериализация

- `@Serializable` на: `PoolEntry` (polymorphic `classDiscriminator = "kind"`, варианты `"success"` / `"http_error"`), `ResponsePart` (polymorphic `"text"` / `"thinking"` / `"tool_call"`), `TimingProfile`, `RangeMs`, `RangeInt`, `FinishReason`, `ErrorBody`, `MidStreamError`, `MidStreamErrorKind`, `SuccessEntry`, `HttpErrorEntry`
- Стрим-события и `RequestContext` НЕ сериализуем — только рантайм

## Files/Areas

- `src/main/kotlin/com/faker/llm/domain/ResponsePart.kt` — sealed ResponsePart
- `src/main/kotlin/com/faker/llm/domain/PoolEntry.kt` — sealed PoolEntry + SuccessEntry + HttpErrorEntry
- `src/main/kotlin/com/faker/llm/domain/TimingProfile.kt` — TimingProfile + RangeMs + RangeInt + ext-методы random
- `src/main/kotlin/com/faker/llm/domain/Usage.kt` — UsageStub + FinishReason
- `src/main/kotlin/com/faker/llm/domain/ErrorModel.kt` — ErrorBody, MidStreamError, MidStreamErrorKind
- `src/main/kotlin/com/faker/llm/domain/RequestContext.kt` — RequestContext
- `src/main/kotlin/com/faker/llm/domain/StreamEvent.kt` — AbstractStreamEvent

## Key Points

- Никаких импортов из `adapter/*` или Ktor в `domain`. Это provider-agnostic ядро.
- HTTP error и mid-stream error — это разные сущности: первая — полностью альтернативный ответ (нет SSE вообще), вторая — часть успешного стрима, прерванного на полпути. Не смешивать.
- `MidStreamErrorKind.AbruptDisconnect` на стороне ядра выглядит как `StreamError`-событие; адаптер при получении перестаёт писать в канал и закрывает его без `[DONE]` / `message_stop`.
- `ToolCallStart.callId` генерится в engine (Task 05), не в пуле — в пуле только template.
- `argsTemplate` поддерживает плейсхолдеры (`${random:int:1:100}`, `${request:tool_name}` и т.п.) — синтаксис заложить, но подстановку реализуем в Task 05. Сейчас просто `JsonObject` без логики.
- Не плодить методы в `domain` — только data + минимальные ext-функции для `RangeMs.randomIn()` / `RangeInt.randomIn()`.

## Done When

- [ ] Все указанные файлы созданы, проект компилируется без ошибок и warning-ов
- [ ] Sealed-иерархии `ResponsePart`, `PoolEntry`, `AbstractStreamEvent` экспаустивно покрывают перечисленные варианты (проверяется через статическую компиляцию `when`-выражения в любом месте кода, либо ручным review)
- [ ] Round-trip сериализации валидируется вручную: написать в `main` или временно в `Main.kt` 4 кейса — success с text+thinking, success с tool-call, success с mid-stream-error (`AbruptDisconnect`), http_error (429 с `rate_limit_error` body) — и проверить, что `decode(encode(x)) == x`. После проверки временный код удалить.
- [ ] В пакете `domain` нет ни одного импорта из `io.ktor.*` или из `com.faker.llm.adapter.*`
- [ ] `RangeMs.randomIn()` / `RangeInt.randomIn()` корректно обрабатывают вырожденный случай `min == max` (возвращают это значение, без `IllegalArgumentException`)
