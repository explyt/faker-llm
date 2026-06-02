# Task 10: Manual Verification

**Type:** Verification

## Goal

Прогнать вручную набор сценариев через `curl` против запущенного фейкера, чтобы убедиться, что оба endpoint-а, оба режима (streaming / non-streaming), tool calls, thinking, prompt directives и error injection работают end-to-end. Компенсация отказа от тестов в MVP — последний шанс поймать косяки до load-test.

## What to Do

### Подготовка

- Запустить фейкер через `run_configuration` IDE-runner (NOT terminal)
- Все `curl` команды — против `http://localhost:8080`

### Сценарии (OpenAI)

#### 10.1 Non-streaming, обычный chat

```bash
curl -sS http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"Hello!"}]}'
```

**Ожидаемо**: `200 OK`, JSON с `id` (`chatcmpl-...`), `object="chat.completion"`, `choices[0].message.content` непустой, `usage` присутствует.

#### 10.2 Streaming, обычный chat

```bash
curl -sS -N http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"Hello!"}],"stream":true}'
```

**Ожидаемо**: серия `data: {...}\n\n` чанков, последний `data: [DONE]\n\n`. Видны delays между чанками.

#### 10.3 Tool call (forced через directive)

```bash
curl -sS -N http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"[[faker:force_tag:tool_call]] What is the weather in Paris?"}],"tools":[{"type":"function","function":{"name":"get_weather","description":"Get weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}],"stream":true}'
```

**Ожидаемо**: streaming-чанки с `delta.tool_calls`, имя tool — `get_weather` (из request), `finish_reason: "tool_calls"` в финальном.

#### 10.4 Force HTTP 429

```bash
curl -sS -i http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"[[faker:force_status:429]] test"}]}'
```

**Ожидаемо**: `HTTP/1.1 429`, body `{"error":{"type":"rate_limit_error","message":"..."}}`.

#### 10.5 Force entry id (ошибочный)

```bash
curl -sS -i http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"[[faker:force_id:nonexistent-id]] test"}]}'
```

**Ожидаемо**: `HTTP/1.1 500` с `{"error":{"type":"pool_misconfigured", ...}}`.

### Сценарии (Anthropic)

#### 10.6 Non-streaming

```bash
curl -sS http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -H "anthropic-version: 2023-06-01" \
  -d '{"model":"claude-fake","max_tokens":1024,"messages":[{"role":"user","content":"Hello"}]}'
```

**Ожидаемо**: `200 OK`, JSON с `id` (`msg_...`), `type="message"`, `content[]` с минимум одним блоком, `stop_reason`.

#### 10.7 Streaming

```bash
curl -sS -N http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-fake","max_tokens":1024,"messages":[{"role":"user","content":"Hello"}],"stream":true}'
```

**Ожидаемо**: `event: message_start` → `event: content_block_start` → серия `event: content_block_delta` → `event: content_block_stop` → `event: message_delta` → `event: message_stop`.

#### 10.8 Thinking (force_tag)

```bash
curl -sS -N http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-fake","max_tokens":1024,"messages":[{"role":"user","content":"[[faker:force_tag:reasoning]] Solve this"}],"stream":true}'
```

**Ожидаемо**: `content_block_start` с `content_block.type="thinking"` и `content_block_delta` с `delta.type="thinking_delta"`, потом отдельный блок с `text`.

#### 10.9 Tool use

```bash
curl -sS -N http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-fake","max_tokens":1024,"tools":[{"name":"get_weather","input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}],"messages":[{"role":"user","content":"[[faker:force_tag:tool_call]] Weather?"}],"stream":true}'
```

**Ожидаемо**: `content_block_start` с `content_block.type="tool_use"`, `id`, `name="get_weather"`, затем `content_block_delta` с `delta.type="input_json_delta"`.

#### 10.10 Mid-stream error

```bash
curl -sS -N http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-fake","messages":[{"role":"user","content":"[[faker:force_tag:mid_stream_error]] test"}],"stream":true}'
```

**Ожидаемо**: серия normal-чанков, потом либо abrupt disconnect (curl завершится без `[DONE]`), либо `event: error` фрейм, либо `data: {` сломанный фрейм.

#### 10.11 Connection cancellation

```bash
curl -sS -N --max-time 1 http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-fake","max_tokens":1024,"messages":[{"role":"user","content":"[[faker:force_tag:long]] long story"}],"stream":true}' | head -5
```

**Ожидаемо**: несколько событий, curl закрывает соединение по таймауту. На сервере НЕТ hung-корутин (`jcmd <pid> Thread.print | grep -i faker` — без зависших).

### Чек-лист отчёта

После прогона зафиксировать в PLAN.md (Shared Context) **итог** каждого сценария: `OK` / `FAIL: ...`. При FAIL — открыть новую задачу на фикс или принять как known limitation.

## Files/Areas

- НЕТ изменений в коде. Verification-task
- Опционально: `docs/manual-checks.md` — снапшот команд+ожиданий для повторных прогонов

## Key Points

- Последняя возможность поймать косяки до load-test
- `-N` в curl — отключение buffering, без него streaming не виден
- Если directive не срабатывает — проверить, что `inspectableContent` собрался (временный `log.debug` в адаптере)
- `force_id` требует реального id из `resources/pool/*.json` — взять любой из Task 05
- При непонятных fail-ах — фиксировать stack trace из application-логов

## Done When

- [ ] Все 11 сценариев прогнаны вручную
- [ ] Каждый сценарий зафиксирован как OK / FAIL в Shared Context PLAN.md
- [ ] При FAIL — открыт новый task на фикс, либо принят как known limitation
- [ ] (Опционально) `docs/manual-checks.md` создан с готовыми curl-командами
