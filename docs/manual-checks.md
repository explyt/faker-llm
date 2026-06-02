# Manual checks (Task 10)

Curl-сценарии для регрессионной проверки фейкера. Перед запуском поднять сервер:

```bash
gradle run                        # default port 8080
PORT=8081 gradle run              # override
```

Все примеры — против `http://localhost:8080`. Для streaming-сценариев нужен флаг `-N`
(отключает curl-буферизацию), иначе SSE-фреймы не будут видны построчно.

---

## OpenAI (`/v1/chat/completions`)

### 10.1 Non-streaming chat

```bash
curl -sS http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"Hello!"}]}'
```

Ожидается: `200`, `chatcmpl-…`, `object:"chat.completion"`, непустой `choices[0].message.content`, `usage` присутствует.

### 10.2 Streaming chat

```bash
curl -sS -N http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"Hello!"}],"stream":true}'
```

Ожидается: серия `data: {...}\n\n`, финал `data: [DONE]\n\n`. Между чанками видны задержки.

### 10.3 Tool call (forced)

```bash
curl -sS -N http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model":"gpt-4o-fake",
    "messages":[{"role":"user","content":"[[faker:force_tag:tool_call]] What is the weather in Paris?"}],
    "tools":[{"type":"function","function":{"name":"get_weather","description":"Get weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}],
    "stream":true
  }'
```

Ожидается: первый tool-фрейм с `id+type+function.name="get_weather"`, последующие с `function.arguments`, финальный `finish_reason:"tool_calls"`.

### 10.4 Force HTTP 429

```bash
curl -sS -i http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"[[faker:force_status:429]] test"}]}'
```

Ожидается: `HTTP/1.1 429`, `{"error":{"message":"…","type":"rate_limit_error","code":null,"param":null}}`.

### 10.5 Force entry id (несуществующий)

```bash
curl -sS -i http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"[[faker:force_id:nonexistent-id]] test"}]}'
```

Ожидается: `HTTP/1.1 500`, `{"error":{"message":"No applicable pool entry: …","type":"pool_misconfigured", …}}`.

---

## Anthropic (`/v1/messages`)

### 10.6 Non-streaming

```bash
curl -sS http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -H "anthropic-version: 2023-06-01" \
  -d '{"model":"claude-fake","max_tokens":1024,"messages":[{"role":"user","content":"Hello"}]}'
```

Ожидается: `200`, `msg_…`, `type:"message"`, `content[]` ≥ 1 блок, `stop_reason`, `stop_sequence:null`, `usage.{input,output}_tokens`.

### 10.7 Streaming

```bash
curl -sS -N http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-fake","max_tokens":1024,"messages":[{"role":"user","content":"Hello"}],"stream":true}'
```

Ожидается: цепочка `event: message_start` → `event: content_block_start` → N×`event: content_block_delta` → `event: content_block_stop` → `event: message_delta` → `event: message_stop`.

### 10.8 Thinking (force_tag:reasoning)

```bash
curl -sS -N http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-fake","max_tokens":1024,"messages":[{"role":"user","content":"[[faker:force_tag:reasoning]] Solve this"}],"stream":true}'
```

Ожидается: первый `content_block_start` с `content_block.type:"thinking"` + серия `thinking_delta`, затем закрытие thinking-блока и второй `content_block_start` с `type:"text"` + `text_delta`.

### 10.9 Tool use

```bash
curl -sS -N http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{
    "model":"claude-fake","max_tokens":1024,
    "tools":[{"name":"get_weather","input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}],
    "messages":[{"role":"user","content":"[[faker:force_tag:tool_call]] Weather?"}],
    "stream":true
  }'
```

Ожидается: `content_block_start` с `content_block.type:"tool_use"`, `id`, `name:"get_weather"`, `input:{}`. Далее `content_block_delta` с `delta.type:"input_json_delta"`, в `message_delta` финиш `stop_reason:"tool_use"`.

---

## Cross-cutting

### 10.10 Mid-stream error

```bash
curl -sS -N http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"[[faker:force_tag:mid_stream_error]] test"}],"stream":true}'
```

Ожидается (одно из трёх — зависит от weighted pick): `AbruptDisconnect` — стрим обрывается без терминатора; `ErrorEvent` — последний фрейм `event: error\ndata: {...}`; `MalformedJson` — последний фрейм `data: {` сломанный. Главное: **`[DONE]` не появляется**.

Чтобы покрыть все 3 kind'а, прогнать команду 3-5 раз.

### 10.11 Connection cancellation

```bash
curl -sS -N --max-time 1 http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-fake","max_tokens":1024,"messages":[{"role":"user","content":"[[faker:force_tag:long]] long story"}],"stream":true}'
```

Ожидается: несколько событий, потом curl закрывает соединение по таймауту. **На сервере нет hung-корутин** — проверить через `jstack <pid> | grep -E "FlowKt|com.faker.llm"` (не должно быть зависших стеков), `curl -sf http://localhost:8080/healthz` после теста возвращает `ok`.

---

## Known limitations

- **Mid-stream error в non-streaming режиме** — клиент получит `HTTP 200` с **обрезанным** контентом (text окончится посреди слова) и дефолтным `finish_reason`/`stop_reason`. Это сознательно: mid-stream error семантически про streaming-обрыв, в non-stream-collect он становится «срезом» собранных событий. Если нужен явный 5xx — используй `force_status:5xx`.
