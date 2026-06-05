# План миграции форматов на body-контракт

> Контекст: OpenAI-совместимый `POST /v1/chat/completions` уже приведён к body-контракту
> (`faker-contract.md`): директива и `request_id` едут в теле запроса (`x_faker.directive`,
> `request_id`), эхо `request_id` + `x_faker.applied_timing` — в теле ответа (первый/финальный
> чанк, корень нестрима, корень ошибки), `total_ms` **измеряется**, `itl_ms` реально
> выдерживается на каждый токен. Этот документ описывает миграцию **остальных форматов**:
> Anthropic Messages (`/v1/messages`) и OpenAI Response API (`/v1/responses`).

## 0. Статус и важная оговорка

- На стороне нагрузочного клиента (`LoadTesting/internal/wire/`) сейчас есть **только**
  `openai.go`. Wire-адаптеров для Anthropic и Response API там **нет**, и контракт
  (`faker-contract.md` §10) приводит пример только для `wire openai`.
- Поэтому **точное размещение** body-эха (`request_id`, `x_faker.applied_timing`) в SSE-схемах
  Anthropic и Response API контрактом **не зафиксировано**. Эти точки — в разделе
  «Открытые вопросы» каждого формата; их нужно согласовать с командой нагрузки **до**
  реализации (правило: что не зафиксировано в контракте — согласуем, не выдумываем).

## 1. Предварительный шаг — вынести общую инфраструктуру (refactor, без смены поведения)

Сейчас OpenAI- и Anthropic-адаптеры дублируют структуру роутов/мапперов. Перед второй
миграцией стоит поднять общее в shared-слой, чтобы не плодить копии:

1. **`app/RequestTimer.kt`** — `rememberRequestId` / `rememberedRequestId` уже общие. ✓
2. **Планируемый тайминг для эха** — `plannedTtftMs`/`plannedItlMs` (midpoint диапазонов
   `entry.timing`) сейчас приватны в `OpenAiRoutes`. Вынести в общий хелпер (напр.
   `app/AppliedTiming.kt`: `fun plannedTiming(entry: SuccessEntry): Pair<Long, Long>`),
   переиспользовать во всех адаптерах.
3. **Измеренный total** — паттерн «`elapsedMsSince(requestStartNanos)` в момент финального
   фрейма» одинаков для всех. Зафиксировать как соглашение (хелпер уже есть).
4. **`BodyDirectivePolicy`** уже провайдер-агностична (читает `ctx.directive`). Для нового
   адаптера достаточно, чтобы его request-mapper заполнял `ctx.directive` из тела.
5. **Удалить legacy** после миграции Anthropic: `estimateForStreaming`, `APPLIED_TIMING_HEADER`,
   `fromElapsed`, `toHeaderValue`, `HeaderDirectivePolicy`, поле `RequestContext.directiveHeader`,
   а также `faker_elapsed_ms` из Anthropic-DTO. Пока их держит только Anthropic.

## 2. Anthropic Messages (`POST /v1/messages`)

Текущее состояние: директива и `request_id` — из **заголовков** (`X-Faker-Directive`,
`X-Request-Id`), эхо — заголовками + `faker_elapsed_ms` в теле; `estimateForStreaming`
пишет оценку тайминга заголовком до тела. Всё это нужно заменить на body-контракт.

### Шаги

1. **DTO запроса** (`adapter/anthropic/dto/Request.kt`): добавить top-level `request_id: String?`
   и `x_faker: XFakerRequest?` (переиспользовать общий `XFakerRequest{ directive }`).
2. **`AnthropicRequestMapper.toContext`**: заполнять `ctx.directive = request.x_faker?.directive`;
   убрать параметр `directiveHeader`.
3. **`AnthropicRoutes`**: `request_id` — из тела, `call.rememberRequestId(...)`; убрать чтение
   заголовков, `echoRequestId`, `appendAppliedTiming`, `estimateForStreaming`. Передавать
   `requestId` + плановые ttft/itl в маппер. Сигнатуру `requestIdHeader` убрать.
4. **DTO ответа** (`StreamEvents.kt`, `Response.kt`, `ErrorBody.kt`): убрать `faker_elapsed_ms`;
   добавить `request_id` (первое событие) и `x_faker.applied_timing` (финальное событие) —
   **точное событие см. «Открытые вопросы»**.
5. **`AnthropicResponseMapper`**: `request_id` — в первое эмитнутое событие; `x_faker.applied_timing`
   с **измеренным** `total_ms` — в финальное; убрать `EmitTimer`/`faker_elapsed_ms`. Нестрим —
   `request_id` + `x_faker` в корень. Ошибка — в корень (`0/0/0`).
6. **Per-token itl**: убедиться, что синтетические Anthropic-ответы тоже режутся по одному
   токену на чанк (та же логика `SyntheticEntryBuilder` уже общая — Anthropic использует тот
   же движок и `SuccessEntry`, так что трап (а) уже закрыт; проверить тестом на длительность).
7. **`ErrorHandling.kt`**: убрать спец-ветку Anthropic (legacy-заголовки) — обе ветки станут
   идентичны (body-эхо), останется только провайдер-специфичная форма конверта.
8. **Main.kt**: убрать `HeaderDirectivePolicy` из роутера и `requestIdHeader` из проводки.

### Открытые вопросы (согласовать с командой нагрузки)

- В каком Anthropic SSE-событии нести `request_id`? Кандидат — `message_start` (приходит
  первым, переживает обрыв).
- В каком событии нести `x_faker.applied_timing`? Кандидаты — `message_delta` (с финальным
  `usage`) или `message_stop`. Нужно, чтобы клиентский Anthropic-парсер читал именно его.
- Форма поля в Anthropic-теле: тот же `x_faker.applied_timing{ttft_ms,itl_ms,total_ms}` и
  top-level `request_id`, или вложенные иначе?
- Маппинг `finish_reason`: Anthropic использует `stop_reason` (`end_turn`/`tool_use`/...).
  Подтвердить ожидания клиента для tool_call (аналог `tool_calls`).

## 3. OpenAI Response API (`POST /v1/responses`) — новый адаптер

Формата сейчас в кодовой базе нет. Это **новый адаптер** `adapter/responses/` по той же
гексагональной схеме (DTO + RequestMapper + ResponseMapper + Routes), переиспользующий ядро
(`domain`/`pool`/`routing`/`engine`) и `SyntheticEntryBuilder`.

### Шаги

1. **Роут** `POST /v1/responses`, зарегистрировать в `Main.module`.
2. **DTO запроса**: Responses-схема (`model`, `input` как строка/массив items, `stream`,
   `tools`, ...). Добавить body-контракт: `request_id`, `x_faker.directive` (общий тип).
3. **RequestMapper**: извлечь inspectable-текст из `input`; `toolNames` из `tools`;
   `ctx.directive` из тела. `BodyDirectivePolicy` подхватит без изменений.
4. **ResponseMapper**: смэппить `Flow<AbstractStreamEvent>` в Responses-wire:
   - стрим — событийная модель `response.*` (`response.created`, `response.output_text.delta`,
     reasoning-дельты, `response.completed`); SSE `data: {json}\n\n` + терминатор;
   - reasoning → отдельный канал Responses (не `<think>`);
   - нестрим — один JSON `response` с `output[]`.
5. **Body-эхо**: `request_id` — в первое событие/корень; `x_faker.applied_timing` (измеренный
   `total_ms`) — в финальное событие/корень; ошибка — в корень. Per-token itl — через общий
   движок (уже закрыт).
6. **Тесты**: маппер (эхо в нужных местах, reasoning-канал, finish/terminator), длительность
   (виртуальное время), измеренный total, ошибка.

### Открытые вопросы (согласовать с командой нагрузки)

- Точные имена событий Responses-стрима, которые читает клиент (`response.output_text.delta`
  vs `response.content_part.*`), и где он ждёт `request_id` / `x_faker.applied_timing`.
- Имя reasoning-канала в Responses (как клиент детектит thinking-блок).
- Эквивалент `finish_reason`/`usage` в Responses-схеме (`response.completed.usage`?).
- Нужен ли отдельный wire-режим в клиенте (`wire responses`) и его дефолтный путь.

## 4. Пейсинг на границах частей (закрыто)

Изначально движок терял один `itl` на стыке частей. Исправлено в `DefaultStreamingEngine`:
меж-токенная пауза гейтится по **глобальному** счётчику клиентских токенов, а не по
попартовому индексу — пауза стоит перед каждым видимым клиентом токеном, кроме самого первого
в стриме (его лид — TTFT). Покрывает оба стыка:
- **reasoning→content** (`emitTextLike`, гейт `chunksSoFar() > 0`);
- **text/thinking→ToolCall**: `ToolCallStart` — это клиентский токен (несёт `function.name`), но
  он НЕ инкрементит `emittedChunks`, поэтому перед ним стоит явный гейт `chunksSoFar() > 0`, а
  args-чанки паузятся безусловно (всегда следуют за несчитанным `ToolCallStart`).

Тесты на виртуальном времени: `SyntheticEntryBuilderTest` (длительность normal/thinking ==
`total_ms`) и `DefaultStreamingEngineToolCallPacingTest` (стык text→ToolCall и отсутствие двойной
паузы в чистом tool_call). Для будущих адаптеров (Anthropic/Responses), переиспользующих движок,
эта корректность уже обеспечена.

## 5. Definition of Done (на каждый формат)

- [ ] Директива и `request_id` читаются из тела; заголовки не участвуют.
- [ ] `request_id` эхо в первом фрейме/корне; `x_faker.applied_timing` в финальном/корне; ошибка — в корне (`0/0/0`).
- [ ] `total_ms` измеряется (не копируется из директивы); `itl_ms` реально выдерживается на токен (тест на виртуальном времени).
- [ ] `finish_reason`/аналог обязателен при наличии токенов; `stream` чтится.
- [ ] Юнит-тесты + wire-smoke зелёные; `./gradlew test shadowJar`.
- [ ] Открытые вопросы формата закрыты согласованием с командой нагрузки.
