# Контракт Faker LLM ↔ нагрузочный скрипт (предложение)

> Это **предложение** со стороны команды нагрузочного тестирования. Просьба
> согласовать/поправить — без реализации этого контракта режим `faker` не заработает.
> Эталон поведения — `cmd/stubserver` (используйте как референс).

## 1. Назначение

В режиме `faker` каждый запрос несёт **директиву** с ожидаемым типом ответа. Faker
формирует ответ этого типа, а скрипт детерминированно проверяет соответствие под
нагрузкой. Это отделяет проверку *корректности обвязки* (шлюз, лицензия, request-id)
от качества живой модели.

## 2. Транспорт директивы

- Директива едет **только** в HTTP-заголовке запроса `X-Faker-Directive` со
  значением-JSON (схема — §3). Тело запроса остаётся обычным OpenAI-совместимым
  `chat/completions` и директивой не затрагивается.
- Почему заголовок: не зависит от wire-формата (OpenAI / Anthropic / Responses) и не
  загрязняет схему тела. (Если удобнее парсить тело — обсуждаемо.)
- **Флаг `stream` тела запроса Faker обязан чтить:** `true` → SSE-ответ; `false` →
  один JSON. Формат ответа — §6.

## 3. Схема директивы (JSON)

Директива — **размеченное объединение (ADT)**: поле `type` выбирает вариант, и в
объекте присутствуют **только** поля этого варианта.

```jsonc
// normal / thinking — стримовый ответ, длительность задаётся таймингом:
{ "type": "normal",   "timing": { "ttft_ms": 300, "itl_ms": 20, "total_ms": 2000 } }
{ "type": "thinking", "timing": { "ttft_ms": 300, "itl_ms": 20, "total_ms": 2000 },
                      "thinking": { "min_tokens": 20 } }

// tool_call / empty — один или ноль токенов, значим лишь первый-токен-делей:
{ "type": "tool_call", "timing": { "ttft_ms": 300 }, "tool_call": { "name": "get_weather" } }
{ "type": "empty",     "timing": { "ttft_ms": 300 } }

// error — HTTP-ошибка, без тайминга:
{ "type": "error", "error": { "http_status": 429 } }

// timeout — ответ не завершается, без тайминга:
{ "type": "timeout" }
```

| поле | тип | для каких типов | смысл |
|---|---|---|---|
| `type` | enum | все | какой ответ произвести (список закрыт, см. §5) |
| `timing.ttft_ms` | int | `normal`/`thinking`/`tool_call`/`empty` | задержка до первого токена |
| `timing.itl_ms` | int | `normal`/`thinking` | пауза между токенами |
| `timing.total_ms` | int | `normal`/`thinking` | целевая длительность стрима (§4) |
| `thinking.min_tokens` | int | `thinking` | минимум reasoning-токенов до основного ответа |
| `tool_call.name` | string | `tool_call` | имя функции (клиент **сверяет**) |
| `error.http_status` | int | `error` | HTTP-статус ошибки (код/текст — на усмотрение Faker) |

Все поля кроме `type` опциональны и применяются только к своему типу. Если тайминг
не задан — Faker применяет свои дефолты.

## 4. Размер ответа: по длительности

Размер успешного стримового ответа (`normal`/`thinking`) задаётся **длительностью**:
темп — `timing.ttft_ms` (до первого токена) и `timing.itl_ms` (между токенами), а
число content-токенов Faker выводит так, чтобы стрим длился ~`timing.total_ms`:

```
content_tokens = round((total_ms − ttft_ms) / itl_ms) + 1
```

В этом режиме обязателен `itl_ms > 0`. Пример: `total_ms=2200, ttft_ms=200, itl_ms=20`
→ `round((2200−200)/20)+1 = 101` токен, стрим ~2.2с. (Токенного режима больше нет:
длина управляется только длительностью; `max_tokens` тела запроса служит лишь верхней
границей и не задаёт размер faker-ответа.)

`thinking` так же управляется длительностью: выведенные токены делятся на reasoning-блок
(≥ `thinking.min_tokens`) и последующий content. `tool_call` — один токен-вызов.
`empty` — ноль content-токенов (значим только `ttft_ms`).

## 5. Поведение и проверки по типам (6 типов)

| type | что Faker обязан произвести | что клиент считает корректным |
|---|---|---|
| `normal` | успешный стрим content-токенов (длительность по §4) | не ошибка И ответ непустой |
| `thinking` | reasoning-блок (`delta.reasoning_content`) ≥ `min_tokens`, **затем** content | не ошибка И reasoning-блок наблюдался |
| `tool_call` | один вызов функции (имя = `tool_call.name`), `finish_reason: "tool_calls"` | не ошибка И есть вызов И имя совпадает (если задано) |
| `error` | HTTP-ошибка со статусом `error.http_status` | ответ-ошибка И статус совпадает (если задан) |
| `timeout` | не завершать ответ дольше клиентского таймаута | сработал клиентский таймаут |
| `empty` | структурно валидный ответ без content-токенов | не ошибка |

Тестирование rate-limit (429) делается через `error` со `http_status: 429`; отдельного
типа `rate_limit` нет. Аналогично «медленный» ответ — это `normal` с большим
`total_ms`; отдельного типа `slow` нет.

Ожидаемый «неуспех» для `error`/`timeout` **не** считается перфоманс-ошибкой в
метриках — это ожидаемый исход, а не сбой. Список типов закрыт: неизвестный `type`
отвергается на валидации конфига; если всё же дойдёт до проверки — считается некорректным.

## 6. Формат ответа

**Стриминг (`stream: true`):**
- `Content-Type: text/event-stream`; каждый чанк — строка `data: {json}\n\n`; поток
  завершается строкой `data: [DONE]`.
- content → `choices[0].delta.content`; reasoning → `choices[0].delta.reasoning_content`;
  вызов функции → `choices[0].delta.tool_calls`.
- **`finish_reason` обязателен** в финальном чанке, если выдан хотя бы один токен
  (`"stop"`; для `tool_call` — `"tool_calls"`). Токены без `finish_reason` → клиент
  помечает ответ структурно невалидным.
- При `stream_options.include_usage` — финальный чанк с `usage.completion_tokens` =
  **число фактически отданных токенов** (для `thinking` включает reasoning-токены).

**Нестриминг (`stream: false`):** один JSON `application/json` с `choices[0].message`.

**Ошибки (`error`):** HTTP-статус ≥ 400, тело `{"error":{"message","type","code"}}` +
request-id в теле (§7). Код/тип/текст ошибки Faker выбирает сам (клиент их только
логирует; сверяется лишь HTTP-статус).

## 7. request-id (критично, проверяется под нагрузкой)

Клиент генерирует request-id и шлёт в заголовке (имя настраивается, по умолчанию
`X-Request-Id`; формат `lt-` + 32 hex-символа). Требования:

- **В метадате (заголовке)** — для **всех** ответов, включая успешные.
- **В теле** — для **ошибочных** ответов (HTTP-статус ≥ 400).

Клиент сверяет **точное равенство** отправленному id (не «похоже», не подстрока для
заголовка). Детали проверки:

- Заголовок: точное равенство для любого HTTP-ответа (вкл. 4xx/5xx).
- Тело: достаточно вхождения точной строки id в тело ошибки; успешные тела не проверяются.
- Transport/timeout (HTTP-ответа нет): проверка заголовка неприменима и провалом не считается.

## 8. Тайминг: эхо задержки и оверхед шлюза

Faker **обязан** в каждом ответе, где есть HTTP-ответ, вернуть фактически
применённую задержку — заголовком:

```
X-Faker-Applied-Timing: {"ttft_ms":300,"itl_ms":20,"total_ms":4900}
```

- **`total_ms` здесь — ИЗМЕРЕННОЕ** полное время внутри Faker от приёма запроса до
  отдачи последнего байта: `total_ms = ttft_ms + сумма ITL-пауз (+ обработка)`. Это
  «время апстрима». (Не путать с `timing.total_ms` в директиве из §4 — то *целевая*
  длительность на входе; на практике они ≈ равны.) `ttft_ms`/`itl_ms` — для детальной
  сверки. Для `tool_call`/`empty` (0–1 токен) `total_ms` ≈ `ttft_ms`.
- Клиент считает **оверхед шлюза = E2E − `total_ms` = сеть + шлюз + лицензия**.
  Отрицательный оверхед клампится в 0 (артефакт округления мс против мкс-точного
  E2E) — это маскировка артефакта, а **не** разрешение завышать `total_ms`. Без эха
  оверхед в режиме faker не вычисляется.
- **Где обязателен:** на всех типах с HTTP-ответом, **включая `error`** (чтобы мерить
  оверхед обвязки и на error-пути; стаб отдаёт ошибку мгновенно, поэтому эхо там
  `0/0/0` и весь E2E — это оверхед). **Кроме `timeout`** — там ответа нет.
- В режиме `real` заголовка нет — оверхед не вычисляется.

## 9. Инвариант конфигурации для `timeout`

Faker удерживает ответ дольше клиентского `request_timeout` (эталон: ~600 000 мс).
Клиент считает **сработавший таймаут успехом** для типа `timeout`, а вовремя пришедший
ответ — провалом. ⚠️ Поэтому `request_timeout` в конфиге обязан быть **меньше**
времени удержания, иначе проверка `timeout` никогда не сработает.

## 10. Примеры (wire `openai`)

### A. `normal`, стриминг, длительность ~2.2с

```http
POST /v1/chat/completions HTTP/1.1
Authorization: Bearer <api_key>
Content-Type: application/json
Accept: text/event-stream
X-Request-Id: lt-9f2c1a7b3e4d5f60a1b2c3d4e5f60718
X-Faker-Directive: {"type":"normal","timing":{"ttft_ms":200,"itl_ms":20,"total_ms":2200}}

{ "model": "stub-model",
  "messages": [{ "role": "user", "content": "lorem ipsum ..." }],
  "stream": true, "max_tokens": 256, "stream_options": { "include_usage": true } }
```

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
X-Request-Id: lt-9f2c1a7b3e4d5f60a1b2c3d4e5f60718
X-Faker-Applied-Timing: {"ttft_ms":200,"itl_ms":20,"total_ms":2200}

data: {"object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"word "},"finish_reason":null}]}
  … (всего 101 content-чанк: round((2200−200)/20)+1) …
data: {"object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
data: {"object":"chat.completion.chunk","choices":[],"usage":{"completion_tokens":101}}
data: [DONE]
```

### B. `error` со статусом 429 (путь ошибки / rate-limit)

```http
POST /v1/chat/completions HTTP/1.1
Authorization: Bearer <api_key>
Content-Type: application/json
X-Request-Id: lt-0011223344556677889900aabbccddee
X-Faker-Directive: {"type":"error","error":{"http_status":429}}

{ "model": "stub-model", "messages": [{ "role": "user", "content": "..." }], "stream": true }
```

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
X-Request-Id: lt-0011223344556677889900aabbccddee
X-Faker-Applied-Timing: {"ttft_ms":0,"itl_ms":0,"total_ms":0}

{ "error": { "message": "...", "type": "rate_limit_exceeded", "code": "rate_limit_exceeded" },
  "request_id": "lt-0011223344556677889900aabbccddee" }
```
