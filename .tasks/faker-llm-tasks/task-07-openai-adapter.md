# Task 07: OpenAI Adapter

**Type:** Code Modification

## Goal

Реализовать адаптер для OpenAI `/v1/chat/completions`: DTO, парсинг request → `RequestContext`, маппинг `AbstractStreamEvent` → OpenAI SSE-формат (delta-чанки + `[DONE]`), плюс не-стримящий режим. Покрывает text, reasoning, tool calls, error injection.

## What to Do

### DTO (request)

Файлы в `com.faker.llm.adapter.openai.dto`:

- `ChatCompletionRequest`:
  - `model: String`
  - `messages: List<ChatMessage>`
  - `tools: List<ToolDef>? = null`
  - `stream: Boolean = false`
  - Прочие поля поглощаем через `ignoreUnknownKeys = true`
- `ChatMessage`:
  - `role: String`
  - `content: JsonElement?` — string или массив content-parts
  - `tool_call_id: String? = null`, `tool_calls: JsonElement? = null`
- `ToolDef`: `type: String` (`"function"`) + `function: FunctionDef`
- `FunctionDef`: `name: String`, `description: String? = null`, `parameters: JsonElement? = null`

### DTO (response — non-streaming)

- `ChatCompletionResponse`: `id`, `object="chat.completion"`, `created`, `model`, `choices`, `usage`
- `Choice`: `index=0`, `message`, `finish_reason`
- `AssistantMessage`: `role="assistant"`, `content: String?`, `tool_calls: List<ToolCallResponse>?`
- `ToolCallResponse`: `id`, `type="function"`, `function: FunctionCall`
- `FunctionCall`: `name`, `arguments: String` (**stringified JSON**, не объект)
- `Usage`: `prompt_tokens`, `completion_tokens`, `total_tokens`

### DTO (streaming chunk)

- `ChatCompletionChunk`: `id` (тот же на весь стрим), `object="chat.completion.chunk"`, `created`, `model`, `choices`
- `ChunkChoice`: `index=0`, `delta`, `finish_reason: String?`
- `ChunkDelta`: `role: String?` (только в первом: `"assistant"`), `content: String?`, `tool_calls: List<ToolCallDelta>?`, `reasoning: String?`
- `ToolCallDelta`: `index=0`, `id: String?` (только в первом), `type: String?` (только в первом), `function: FunctionDelta`
- `FunctionDelta`: `name: String?` (только в первом), `arguments: String?` (растёт по чанкам)

### Парсинг request → `RequestContext`

В `OpenAiRequestMapper`:

- `fun toContext(request: ChatCompletionRequest): RequestContext`
- `hasTools` = `request.tools?.isNotEmpty() == true`
- `toolNames` = `request.tools?.map { it.function.name } ?: emptyList()`
- `stream` = `request.stream`
- `model` = `request.model`
- `inspectableContent` — concat `content` из всех `messages` с `role in {"user", "system"}`. Если `content` это string — берём; если JsonArray — собираем `text` из элементов с `type="text"`. Joiner — `"\n"`

### Маппинг `AbstractStreamEvent` → OpenAI

#### Non-streaming

- Собрать события через `toList()` в полный `ChatCompletionResponse`
- `content` = concat всех `TextChunk.delta`
- Reasoning (`ThinkingChunk`) в non-streaming — known limitation: либо игнорируем тихо, либо конкатим в `content` с обёрткой `<think>...</think>` (на усмотрение реализатора)
- `tool_calls` — собрать из `ToolCallStart` / `ToolCallArgsChunk` / `ToolCallEnd`
- `usage.prompt_tokens = usage.promptChars / 4`, `completion_tokens = usage.completionChars / 4`, `total = sum`
- `finish_reason`: `Stop→"stop"`, `Length→"length"`, `ToolCalls→"tool_calls"`, `Error→"stop"`

#### Streaming

- `respondTextWriter(ContentType("text", "event-stream"))` либо Ktor SSE plugin (по версии)
- На каждый `AbstractStreamEvent` — `data: <json>\n\n`:
  - `StreamStart` → chunk с `delta.role = "assistant"`, content=null
  - `TextChunk(d)` → chunk с `delta.content = d`
  - `ThinkingChunk(d)` → chunk с `delta.reasoning = d`
  - `ToolCallStart(name, callId)` → chunk с `delta.tool_calls = [{ index: 0, id: callId, type: "function", function: { name } }]`
  - `ToolCallArgsChunk(d)` → chunk с `delta.tool_calls = [{ index: 0, function: { arguments: d } }]`
  - `ToolCallEnd(_)` → ничего не пишем
  - `StreamError(kind, body)`:
    - `AbruptDisconnect` → flush + close
    - `ErrorEvent` → `event: error\ndata: {"error": {"type": body.type, "message": body.message}}\n\n` потом close
    - `MalformedJson` → `data: {\n\n` потом close
  - `StreamEnd(reason, usage)` → финальный chunk с `delta = {}`, `finish_reason = mapping`, потом `data: [DONE]\n\n`
- Flush после каждого фрейма

### HTTP-error entry — отдельный путь

В `OpenAiRoutes`:

- После `PoolSelector.pick(ctx, decision)`, если entry — `HttpErrorEntry`:
  - `delay(entry.preResponseDelayMs.randomIn())`
  - `call.respond(HttpStatusCode.fromValue(entry.status), OpenAiErrorBody(...))`
  - НЕ запускаем engine

OpenAI error body:
```json
{"error": {"message": "...", "type": "...", "code": null, "param": null}}
```

### Endpoint

- `POST /v1/chat/completions` — единственный в первой итерации
- При `request.stream = true` — streaming-путь, иначе non-streaming
- Чтение body через `call.receive<ChatCompletionRequest>()`

## Files/Areas

- `src/main/kotlin/com/faker/llm/adapter/openai/dto/Request.kt`
- `src/main/kotlin/com/faker/llm/adapter/openai/dto/Response.kt`
- `src/main/kotlin/com/faker/llm/adapter/openai/dto/Chunk.kt`
- `src/main/kotlin/com/faker/llm/adapter/openai/dto/ErrorBody.kt`
- `src/main/kotlin/com/faker/llm/adapter/openai/OpenAiRequestMapper.kt`
- `src/main/kotlin/com/faker/llm/adapter/openai/OpenAiResponseMapper.kt`
- `src/main/kotlin/com/faker/llm/adapter/openai/OpenAiRoutes.kt` — Ktor `Route.openAiRoutes(...)` extension

## Key Points

- `FunctionCall.arguments` — **stringified JSON**, особенность OpenAI. Сериализуем `argsTemplate` через `Json.encodeToString`
- `chatcmpl-id` фиксированный на весь стрим — генерится один раз
- `created` — `System.currentTimeMillis() / 1000` один раз в начале
- ContentType для SSE: `text/event-stream; charset=utf-8`. Заголовки: `Cache-Control: no-cache`, `Connection: keep-alive`
- Между фреймами — `\n\n` (SSE spec)
- Cancellation: Flow в engine автоматически отменяется через корутинный механизм при закрытии соединения
- `Cache-Control: no-cache` обязателен — без него прокси буферизирует стрим

## Done When

- [ ] `POST /v1/chat/completions` принимает валидный OpenAI request
- [ ] Non-streaming mode возвращает корректный `ChatCompletionResponse` с `content` / `tool_calls`
- [ ] Streaming mode возвращает SSE-поток chunks, заканчивающийся `data: [DONE]\n\n`
- [ ] Tool-call в streaming: первый chunk с `id`+`type`+`function.name`, последующие с `function.arguments`
- [ ] Reasoning маппится в `delta.reasoning` в streaming
- [ ] `HttpErrorEntry` возвращает HTTP-статус с OpenAI error body, без стрима
- [ ] Mid-stream errors корректно прерывают SSE (abrupt / error event / malformed json)
- [ ] `inspectableContent` собирается из user+system messages (включая multimodal arrays)
- [ ] Ручная проверка через `curl` (Task 10): non-streaming, streaming, tool-call request, force directive в промпте
