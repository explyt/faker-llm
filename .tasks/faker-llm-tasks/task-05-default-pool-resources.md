# Task 05: Default Pool Resources

**Type:** Code Modification

## Goal

Положить в `resources/pool/` стартовый набор JSON-файлов с pool entries, покрывающий все сценарии, ради которых пишется фейкер: разные длины ответов, reasoning-блоки, tool calls, mid-stream errors, HTTP errors. Каждой категории — свой `tag`, чтобы router мог их форсить через `[[faker:force_tag:...]]`.

## What to Do

### Структура директории

`src/main/resources/pool/` — один файл на категорию:

- `01-short-replies.json` — короткие ответы (1-2 предложения), низкий TTFT
- `02-medium-replies.json` — средние (абзац-два)
- `03-long-replies.json` — длинные (несколько абзацев)
- `04-huge-reasoning.json` — большой `Thinking` + короткий финальный `Text` (имитация o1 / Claude Thinking)
- `05-mixed-text-thinking.json` — чередование `Thinking` и `Text`
- `06-tool-calls.json` — entries с `ToolCall`, `requiresTools = true`, `finishReason = "ToolCalls"`
- `07-mid-stream-errors.json` — success-entries с `midStreamError`
- `08-http-errors.json` — HTTP-ошибки 429/500/503/529/504

### Теги (для force_tag)

- `short`, `medium`, `long`, `reasoning`, `mixed`, `tool_call`, `mid_stream_error`, `http_error`

Каждая entry получает соответствующий `tag`. Это даёт детерминированную траекторию через prompt directive.

### Распределение весов

- ~70% → `short` + `medium` + `long`
- ~10% → `huge-reasoning` + `mixed-text-thinking`
- ~10% → `tool-calls` (применяются только при `tools` в request)
- ~5% → `mid-stream-errors`
- ~5% → `http-errors`

Конкретные веса — на усмотрение реализатора, главное порядок величин.

### Timing-профили

- TTFT: `100-800ms` для коротких, `200-1500ms` для средних/длинных, `500-3000ms` для reasoning
- inter-chunk: `10-50ms`
- chunk size: `2-8` символов на чанк

### Tool-call entries

В `06-tool-calls.json`:
- Минимум 3 entries, разные `argsTemplate` (пустой `{}`, объект с одним полем, объект с тремя полями)
- `argsTemplate` — обычный JSON-объект без плейсхолдеров
- Имя tool-а **не указывается** в entry. Engine (Task 06) выбирает случайный из `RequestContext.toolNames`
- `finishReason = "ToolCalls"`, `requiresTools = true`, `tag = "tool_call"`

### HTTP error entries

| Статус | type | message |
|---|---|---|
| 429 | `rate_limit_error` | "Rate limit reached for requests" |
| 500 | `api_error` | "The server encountered an error" |
| 503 | `overloaded_error` | "Service temporarily unavailable" |
| 529 | `overloaded_error` | "Overloaded" |
| 504 | `timeout` | "Request timed out" |

`preResponseDelayMs`:
- 429/500 — `0-100ms`
- 503/529 — `200-1000ms`
- 504 — `5000-15000ms`

### Mid-stream errors

В `07-mid-stream-errors.json` — 3 entries (по одному на каждый `MidStreamErrorKind`):
- `AbruptDisconnect` после ~5 чанков
- `ErrorEvent` после ~10 чанков
- `MalformedJson` после ~3 чанков

### Контент текста

- Lorem ipsum / обычные английские (часть на русском) ответы разной длины
- Reasoning — похожее на CoT ("Let me think step by step. First, ... Then, ...") 5-15 предложений

## Files/Areas

- `src/main/resources/pool/01-short-replies.json`
- `src/main/resources/pool/02-medium-replies.json`
- `src/main/resources/pool/03-long-replies.json`
- `src/main/resources/pool/04-huge-reasoning.json`
- `src/main/resources/pool/05-mixed-text-thinking.json`
- `src/main/resources/pool/06-tool-calls.json`
- `src/main/resources/pool/07-mid-stream-errors.json`
- `src/main/resources/pool/08-http-errors.json`

## Key Points

- Все файлы — JSON-массивы entries (даже если одна entry — для единообразия)
- Дискриминатор: `"kind": "success"` или `"kind": "http_error"`. `ResponsePart.type`: `"text"`, `"thinking"`, `"tool_call"`
- `id` каждой entry — уникальный человекочитаемый (`"short-greeting-01"`, `"http-429-rate-limit-01"`). Понадобится для `force_id`
- Веса — `Double`, не нормируем
- Все тексты корректно эскейпятся в JSON
- НЕ генерировать гигантских entries на сотни KB — это пул, а не датасет
- Все tool-call entries: `requiresTools = true`, `tag = "tool_call"`, `finishReason = "ToolCalls"`. Все non-tool: `requiresTools = false`

## Done When

- [ ] Все 8 файлов созданы, каждый — валидный JSON
- [ ] Success-категории содержат не менее 3 entries
- [ ] HTTP-errors — минимум 5 entries (по одной на каждый перечисленный статус)
- [ ] `PoolLoader.load("pool")` успешно загружает все файлы без warning-ов, итоговое количество entries ≥ 22
- [ ] Распределение весов в общих чертах соответствует таргетным процентам (ручная проверка; допустимо ±10%)
- [ ] Все tool-call entries имеют `requiresTools = true`, `tag = "tool_call"`, `finishReason = "ToolCalls"`
- [ ] У каждой entry проставлен корректный `tag` из перечисленного набора
