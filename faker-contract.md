# Контракт Faker LLM ↔ нагрузочный скрипт (предложение)

> Это **предложение** со стороны команды нагрузочного тестирования. Просьба
> согласовать/поправить — без вашей реализации режим `faker` не заработает.

## Зачем

В режиме `faker` каждый запрос несёт **директиву** с ожидаемым типом ответа.
Faker формирует ответ этого типа, а скрипт детерминированно проверяет
соответствие под нагрузкой. Это отделяет проверку *корректности* обвязки
(шлюз, лицензия, request-id) от качества живой модели.

## Где живёт директива

HTTP-заголовок запроса:

```
X-Faker-Directive: <JSON>
```

Почему заголовок, а не тело: он не зависит от wire-формата (OpenAI / Anthropic /
Responses) и не загрязняет схему запроса. Если вам удобнее парсить тело
(YAML-frontmatter в последнем user-сообщении) — обсуждаемо.

## Схема директивы (JSON)

```jsonc
{
  "type": "normal | error | thinking | tool_call | slow | timeout | rate_limit | empty",
  "error":     { "http_status": 429, "code": "rate_limit_exceeded", "message": "..." },
  "thinking":  { "min_tokens": 20 },
  "tool_call": { "name": "get_weather", "args_keys": ["city"] },
  "tokens":    { "output": 200 },
  "timing":    { "ttft_ms": 300, "itl_ms": 20, "total_ms": 5000 },
  "seed":      12345
}
```

Все поля, кроме `type`, опциональны и применяются только для своего типа.

## Примеры запросов

Ниже — полные HTTP-запросы, как их шлёт скрипт в режиме `faker` (wire `openai`).
Тело — обычный OpenAI-совместимый chat/completions; директива едет **только**
заголовком `X-Faker-Directive`. `system`-сообщение присутствует лишь когда
`system_tokens` дал > 0 токенов.

### Пример 1 — `normal` с `tokens.output`, стриминг

Запрос (клиент берёт размер из профиля `output_tokens {min,max}`, кладёт его и в
`tokens.output` директивы, и в `max_tokens` тела):

```http
POST /v1/chat/completions HTTP/1.1
Host: gateway.example
Authorization: Bearer <api_key>
Content-Type: application/json
Accept: text/event-stream
X-Request-Id: lt-9f2c1a7b3e4d5f60a1b2c3d4e5f60718
X-Faker-Directive: {"type":"normal","tokens":{"output":200},"seed":12345}

{
  "model": "stub-model",
  "messages": [
    { "role": "system", "content": "lorem ipsum dolor sit amet ..." },
    { "role": "user",   "content": "consectetur adipiscing elit sed do ..." }
  ],
  "stream": true,
  "max_tokens": 200,
  "stream_options": { "include_usage": true }
}
```

Ответ (SSE): `tokens.output` content-чанков → финальный чанк с `finish_reason: "stop"`
→ чанк `usage` → `data: [DONE]`:

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
X-Request-Id: lt-9f2c1a7b3e4d5f60a1b2c3d4e5f60718
X-Faker-Applied-Timing: {"ttft_ms":50,"itl_ms":10,"total_ms":2040}

data: {"id":"chatcmpl-001","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"word "},"finish_reason":null}]}

  … (всего 200 content-чанков по числу tokens.output) …

data: {"id":"chatcmpl-001","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"id":"chatcmpl-001","object":"chat.completion.chunk","choices":[],"usage":{"completion_tokens":200}}

data: [DONE]
```

⚠️ **Faker обязан соблюдать `tokens.output`:** сгенерировать именно столько
выходных токенов и отразить это же число в `usage.completion_tokens`. Это делает
размер ответа детерминированным и позволяет клиенту сверять tokens/sec и applied-timing.
Клиент требует непустой ответ и наличие `finish_reason`.

### Пример 2 — `thinking`, стриминг

Запрос несёт `thinking.min_tokens` (минимум reasoning-токенов) и `tokens.output`
(размер основного ответа после размышления):

```http
POST /v1/chat/completions HTTP/1.1
Host: gateway.example
Authorization: Bearer <api_key>
Content-Type: application/json
Accept: text/event-stream
X-Request-Id: lt-7788990011223344556677889900aabb
X-Faker-Directive: {"type":"thinking","thinking":{"min_tokens":20},"tokens":{"output":80},"seed":24680}

{
  "model": "stub-model",
  "messages": [
    { "role": "user", "content": "lorem ipsum dolor sit amet ..." }
  ],
  "stream": true,
  "max_tokens": 80,
  "stream_options": { "include_usage": true }
}
```

Ответ (SSE): сначала reasoning-чанки в поле `delta.reasoning_content` (≥ `min_tokens`),
затем обычные content-чанки, затем `finish_reason: "stop"`, `usage` и `[DONE]`:

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
X-Request-Id: lt-7788990011223344556677889900aabb
X-Faker-Applied-Timing: {"ttft_ms":50,"itl_ms":10,"total_ms":1040}

data: {"id":"chatcmpl-002","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"reasoning_content":"think "},"finish_reason":null}]}

  … (всего 20 reasoning-чанков по числу thinking.min_tokens) …

data: {"id":"chatcmpl-002","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"word "},"finish_reason":null}]}

  … (затем content-чанки по числу tokens.output) …

data: {"id":"chatcmpl-002","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"id":"chatcmpl-002","object":"chat.completion.chunk","choices":[],"usage":{"completion_tokens":80}}

data: [DONE]
```

⚠️ **Faker обязан вернуть reasoning/thinking-блок** (поле `delta.reasoning_content`,
не менее `thinking.min_tokens` токенов) до основного `content`. Клиент помечает
ответ некорректным, если thinking-блок не наблюдался.

### Пример 3 — `rate_limit`, путь ошибки

Здесь `system_tokens` дал 0 → блока `system` нет. Директива несёт спецификацию
ошибки; тело запроса при этом остаётся валидным (`max_tokens` всё равно проставлен).

```http
POST /v1/chat/completions HTTP/1.1
Host: gateway.example
Authorization: Bearer <api_key>
Content-Type: application/json
Accept: text/event-stream
X-Request-Id: lt-0011223344556677889900aabbccddee
X-Faker-Directive: {"type":"rate_limit","error":{"http_status":429,"code":"rate_limit_exceeded","message":"synthetic rate limit from faker"},"seed":67890}

{
  "model": "stub-model",
  "messages": [
    { "role": "user", "content": "lorem ipsum dolor sit amet ..." }
  ],
  "stream": true,
  "max_tokens": 200,
  "stream_options": { "include_usage": true }
}
```

Ожидаемый ответ: HTTP `429` с телом-ошибкой, **несущим тот же request-id**, плюс
эхо тайминга даже на ошибке (упреждающий ответ без апстрим-задержки → нули, весь
E2E засчитывается в оверхед обвязки):

```
X-Request-Id: lt-0011223344556677889900aabbccddee
X-Faker-Applied-Timing: {"ttft_ms":0,"itl_ms":0,"total_ms":0}
```

## Поведение по типам

| type | что должен сделать Faker |
|---|---|
| `normal` | обычный успешный ответ ~`tokens.output` токенов |
| `thinking` | ответ с reasoning/thinking-блоком (≥ `thinking.min_tokens`) |
| `tool_call` | ответ с tool/function call с именем `tool_call.name` |
| `error` | ошибка с `error.http_status` / `error.code` |
| `rate_limit` | ошибка HTTP 429 |
| `slow` | успешный, но медленный ответ по `timing` |
| `timeout` | не завершать ответ дольше клиентского таймаута |
| `empty` | структурно валидный, но пустой ответ |

## Эхо delay (обязательно в режиме faker)

Faker **обязан** в каждом ответе возвращать фактически применённую задержку (свой
delay) — заголовком ответа:

```
X-Faker-Applied-Timing: {"ttft_ms":300,"itl_ms":20,"total_ms":4900}
```

- `total_ms` — **полное время, проведённое в Faker (его delay)**. Это и есть
  «время апстрима». Поля `ttft_ms`/`itl_ms` опциональны (для детальной сверки).
- По нему скрипт считает **оверхед шлюза = E2E − total_ms** (сеть + шлюз + лицензия).
  Без этого эха оверхед в режиме faker посчитать нельзя.

Где должно присутствовать эхо delay:

| ответ | эхо delay |
|---|---|
| `normal` / `slow` / `thinking` / `tool_call` / `empty` | да |
| `error` / `rate_limit` | **да** — чтобы измерить оверхед обвязки даже на ошибке |
| `timeout` | нет (ответ не отдаётся) |

В режиме `real` этого заголовка нет — оверхед там не вычисляется.

### Что именно входит в `total_ms` (важно зафиксировать)

`total_ms` = **всё время от приёма запроса Faker'ом до отдачи последнего байта
ответа**, то есть:

```
total_ms = ttft_ms + сумма всех ITL-пауз (+ собственная обработка Faker)
```

Это **полная задержка апстрима**, а не только искусственная пауза. Тогда оверхед
считается чисто:

```
оверхед_шлюза = E2E(клиента) − total_ms(Faker) = сеть + шлюз + лицензия
```

Пример для `slow` с `ttft_ms=1000`, `itl_ms=30`, 50 выходных токенов:
`total_ms ≈ 1000 + 30 × 49 ≈ 2470`. Если клиент намерил E2E = 2520 мс, то
оверхед = 50 мс.

⚠️ Если Faker положит в `total_ms` только искусственную паузу (без времени отдачи
токенов), оверхед окажется завышенным на время генерации — поэтому шлём именно
**полное** время. Просьба согласовать этот пункт.

## request-id (критично, проверяется под нагрузкой)

Клиент генерирует `request-id` и шлёт в заголовке (имя настраивается, по умолчанию
`X-Request-Id`). Требования к ответам по всему тракту:

- **в метадате (заголовке)** — для **всех** ответов, включая успешные;
- **в теле** — для **ошибочных** ответов.

Скрипт валидирует, что вернулся **именно отправленный** id.

## Эталонная реализация

`cmd/stubserver` реализует этот контракт (для локальной отладки скрипта, без
вашего Faker). Используйте его как референс поведения.
