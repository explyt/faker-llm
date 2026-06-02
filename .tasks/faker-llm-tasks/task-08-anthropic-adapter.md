# Task 08: Anthropic Adapter

**Type:** Code Modification

## Goal

Реализовать адаптер для Anthropic `/v1/messages`: DTO, парсинг → `RequestContext`, маппинг `AbstractStreamEvent` → Anthropic SSE-формат (multi-event-type protocol с `message_start` / `content_block_*` / `message_delta` / `message_stop`), плюс не-стримящий режим. Покрывает text, extended thinking, tool use, error injection.

## What to Do

### DTO (request)

В `com.faker.llm.adapter.anthropic.dto`:

- `MessagesRequest`:
  - `model: String`
  - `max_tokens: Int? = null` (Anthropic требует, но faker мягче — игнорим если нет)
  - `messages: List<AnthropicMessage>`
  - `system: JsonElement? = null` — string или array system blocks
  - `tools: List<AnthropicToolDef>? = null`
  - `stream: Boolean = false`
  - `thinking: JsonElement? = null` — `{"type": "enabled", "budget_tokens": N}`
  - Прочие — `ignoreUnknownKeys = true`
- `AnthropicMessage`: `role: String`, `content: JsonElement` (string или array)
- `AnthropicToolDef`: `name: String`, `description: String? = null`, `input_schema: JsonElement? = null`

### DTO (response — non-streaming)

- `MessagesResponse`: `id` (`"msg_" + 24-char`), `type="message"`, `role="assistant"`, `content: List<AnthropicContentBlock>`, `model`, `stop_reason`, `stop_sequence: String? = null`, `usage`
- `AnthropicContentBlock` — sealed по `type`:
  - `TextBlock(type="text", text: String)`
  - `ThinkingBlock(type="thinking", thinking: String)`
  - `ToolUseBlock(type="tool_use", id: String, name: String, input: JsonObject)`
- `AnthropicUsage`: `input_tokens: Int`, `output_tokens: Int`

### DTO (streaming events)

Anthropic SSE — типизированные `event:` строки. Каждое событие имеет `type` поле в data.

- `MessageStartEvent` (event: `message_start`): `type="message_start"`, `message: MessagesResponse`-shell
- `ContentBlockStartEvent` (event: `content_block_start`): `type="content_block_start"`, `index`, `content_block: AnthropicContentBlock` (text="" / thinking="" / input={})
- `ContentBlockDeltaEvent` (event: `content_block_delta`): `type="content_block_delta"`, `index`, `delta: ContentBlockDelta`
- `ContentBlockDelta` — sealed:
  - `TextDelta(type="text_delta", text: String)`
  - `ThinkingDelta(type="thinking_delta", thinking: String)`
  - `InputJsonDelta(type="input_json_delta", partial_json: String)`
- `ContentBlockStopEvent` (event: `content_block_stop`): `type="content_block_stop"`, `index`
- `MessageDeltaEvent` (event: `message_delta`): `type="message_delta"`, `delta: {stop_reason, stop_sequence}`, `usage: {output_tokens}`
- `MessageStopEvent` (event: `message_stop`): `type="message_stop"`
- `PingEvent` (event: `ping`): `type="ping"` — опционально
- `ErrorEvent` (event: `error`): `type="error"`, `error: {type, message}`

### Парсинг request → `RequestContext`

- `hasTools` = `request.tools?.isNotEmpty() == true`
- `toolNames` = `request.tools?.map { it.name } ?: emptyList()`
- `stream` = `request.stream`
- `model` = `request.model`
- `inspectableContent` — concat:
  - `system` (если string — берём; если array — concat `text` полей)
  - `content` всех `messages` с `role = "user"` (string или array text-blocks)
  - Joiner — `"\n"`

### Маппинг `AbstractStreamEvent` → Anthropic SSE

Каждый блок (`Text`, `Thinking`, `ToolUse`) имеет свой `index`, увеличивающийся на 1. Track в локальном state маппера.

#### Streaming

- `StreamStart` → `event: message_start\ndata: {...}\n\n` — содержит `message`-shell с id/model/usage.input_tokens
- Перед каждым новым type-блоком: `event: content_block_start\ndata: {index: N, content_block: {...}}\n\n`
  - text открывается при первом `TextChunk`, закрывается при появлении другого типа
  - аналогично thinking
  - tool_use открывается на `ToolCallStart`, закрывается на `ToolCallEnd`
- `TextChunk(d)` → `content_block_delta` с `{type: "text_delta", text: d}`
- `ThinkingChunk(d)` → `content_block_delta` с `{type: "thinking_delta", thinking: d}`
- `ToolCallStart(name, callId)` → закрыть предыдущий блок (`content_block_stop`), затем `content_block_start` с `{type: "tool_use", id: callId, name, input: {}}`
- `ToolCallArgsChunk(d)` → `content_block_delta` с `{type: "input_json_delta", partial_json: d}`
- `ToolCallEnd(_)` → `event: content_block_stop\ndata: {index: N}\n\n`
- `StreamError(kind, body)`:
  - `AbruptDisconnect` → flush + close
  - `ErrorEvent` → `event: error\ndata: {type: "error", error: {type: body.type, message: body.message}}\n\n` + close
  - `MalformedJson` → `data: {\n\n` + close
- `StreamEnd(reason, usage)`:
  - Закрыть последний открытый блок (`content_block_stop`)
  - `event: message_delta\ndata: {type: "message_delta", delta: {stop_reason: mapping, stop_sequence: null}, usage: {output_tokens: ...}}\n\n`
  - `event: message_stop\ndata: {type: "message_stop"}\n\n`

`stop_reason`: `Stop→"end_turn"`, `Length→"max_tokens"`, `ToolCalls→"tool_use"`, `Error→"end_turn"`

#### Non-streaming

- Собрать события из Flow, сгруппировать в `content`-блоки (`TextBlock` / `ThinkingBlock` / `ToolUseBlock`)
- Для tool_use — `input` собирается из конкатенации `ToolCallArgsChunk.delta` и парсится через `Json.parseToJsonElement`. Если парсинг падает — `{"raw": "..."}`
- `usage.input_tokens = promptChars / 4`, `output_tokens = completionChars / 4`

### HTTP-error entry — отдельный путь

Anthropic error body:
```json
{"type": "error", "error": {"type": "rate_limit_error", "message": "..."}}
```

В `AnthropicRoutes`:
- После `PoolSelector.pick(ctx, decision)`, если `HttpErrorEntry`:
  - `delay(entry.preResponseDelayMs.randomIn())`
  - `call.respond(HttpStatusCode.fromValue(entry.status), AnthropicErrorBody(...))`

### Endpoint

- `POST /v1/messages` — единственный в первой итерации
- При `request.stream = true` — streaming, иначе non-streaming
- Чтение body через `call.receive<MessagesRequest>()`

### Заголовок `anthropic-version`

- Фейкер **не проверяет** этот заголовок (нагрузочные тесты это шум). Игнорируем

## Files/Areas

- `src/main/kotlin/com/faker/llm/adapter/anthropic/dto/Request.kt`
- `src/main/kotlin/com/faker/llm/adapter/anthropic/dto/Response.kt`
- `src/main/kotlin/com/faker/llm/adapter/anthropic/dto/StreamEvents.kt`
- `src/main/kotlin/com/faker/llm/adapter/anthropic/dto/ErrorBody.kt`
- `src/main/kotlin/com/faker/llm/adapter/anthropic/AnthropicRequestMapper.kt`
- `src/main/kotlin/com/faker/llm/adapter/anthropic/AnthropicResponseMapper.kt`
- `src/main/kotlin/com/faker/llm/adapter/anthropic/AnthropicRoutes.kt`

## Key Points

- Anthropic SSE использует **именованные events** (`event: message_start\ndata: ...`). У OpenAI — только `data:`. Это разные форматы.
- `index` блоков — критичен для клиента (связывает start/delta/stop). Инкрементировать при каждом новом `content_block_start`.
- `ContentBlockDelta` — sealed `@Serializable` с polymorphic `classDiscriminator = "type"`. Аналогично `AnthropicContentBlock`.
- `tool_use.id` — `callId` из engine. Один и тот же в `content_block_start.content_block.id` и не дублируется в `_stop`.
- `partial_json` в `InputJsonDelta` — **stringified JSON фрагмент** (как у OpenAI `arguments`). Engine эмитит `ToolCallArgsChunk(delta)` уже как куски стрингифицированного JSON.
- `thinking` (extended thinking) — это отдельные блоки, не делта внутри text. Engine их различает, адаптер маппит корректно.

## Done When

- [ ] `POST /v1/messages` принимает валидный Anthropic request
- [ ] Non-streaming mode возвращает корректную `MessagesResponse` с `content[]` и `stop_reason`
- [ ] Streaming mode выдаёт корректную SSE-цепочку: `message_start` → `content_block_start/delta/stop` (для каждого блока) → `message_delta` → `message_stop`
- [ ] Tool_use в streaming: `content_block_start` с пустым `input`, серия `input_json_delta`, `content_block_stop`
- [ ] Thinking-блоки идут как отдельные `content_block`-ы с `type="thinking"` и `thinking_delta`
- [ ] `HttpErrorEntry` возвращает HTTP-статус с Anthropic error body
- [ ] Mid-stream errors корректно прерывают SSE
- [ ] `inspectableContent` собирается из user-messages + system (включая array-форматы)
- [ ] Ручная проверка через `curl` (Task 10): non-streaming, streaming, thinking, tool-use
