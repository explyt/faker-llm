# Task 06: Streaming Engine

**Type:** Code Modification

## Goal

Реализовать корутинный движок, который берёт `PoolEntry` + `RequestContext` и эмитит `Flow<AbstractStreamEvent>` с реалистичным таймингом (TTFT, inter-chunk delays). Engine провайдер-агностичный — на выходе абстрактные события, дальше адаптер маппит в свой SSE-формат. Это output bus ядра.

## What to Do

### Engine API

В пакете `com.faker.llm.engine`:

```kotlin
interface StreamingEngine {
    fun execute(entry: PoolEntry, ctx: RequestContext): Flow<AbstractStreamEvent>
}
```

- Возвращается **cold Flow** — материализуется при подписке адаптером
- Внутри — `flow { ... }`-builder с `delay()` между эмитами

### Реализация `DefaultStreamingEngine`

Конструктор: `class DefaultStreamingEngine(private val random: Random = Random.Default) : StreamingEngine`

Логика `execute` по типам `PoolEntry`:

#### Для `SuccessEntry`

1. `emit(StreamStart)`
2. `delay(entry.timing.ttftMs.randomIn())` — Time To First Token
3. Итерация по `entry.parts`:
   - `Text(content)` → разбить на чанки размера `chunkSizeChars.randomIn()`, для каждого: `emit(TextChunk(delta))` + `delay(interChunkMs.randomIn())`
   - `Thinking(content)` → аналогично, но `emit(ThinkingChunk(delta))`
   - `ToolCall(argsTemplate)` →
     - Имя tool-а: `ctx.toolNames.random(random)` (если пуст — `IllegalStateException`, это invariant violation наверху)
     - Сгенерить `callId` (`"call_" + UUID-like 24-char alphanumeric`)
     - `emit(ToolCallStart(toolName, callId))`
     - Сериализовать `argsTemplate` в compact JSON, разбить на чанки и эмитить `ToolCallArgsChunk(delta)` с inter-chunk-задержками
     - `emit(ToolCallEnd(callId))`
4. **Mid-stream error**: если `entry.midStreamError != null`, после эмита `afterChunks`-го общего чанка:
   - `emit(StreamError(kind, ErrorBody(...)))` — body синтетический (`type="stream_error"`, `message="Injected mid-stream error: ${kind}"`)
   - **return** из `flow{}` — НЕ эмитить `StreamEnd`
5. Без midStreamError или если порог не достигнут: `emit(StreamEnd(entry.finishReason, UsageStub(promptChars, completionChars)))`
   - `promptChars` = `ctx.inspectableContent?.length ?: 0`
   - `completionChars` = сумма символов всех эмитнутых text/thinking/tool-args чанков (считается на лету)

#### Для `HttpErrorEntry`

- Engine для HTTP-error entry **не вызывается** — этот случай адаптер обрабатывает напрямую (другой HTTP-статус, нет стрима)
- Если всё же передан `HttpErrorEntry` — `IllegalArgumentException`. Invariant violation

### Чанкирование

Helper `internal fun String.chunkByRange(range: IntRange, random: Random): Sequence<String>`:
- Итеративно отщипывает кусок длины `range.randomIn()` (но не больше остатка)
- Возвращает `Sequence<String>` (lazy)
- Используется для Text/Thinking/ToolCallArgs

### Concurrency / Cancellation

- Flow эмитит на coroutine-context подписчика
- `delay()` — kotlinx.coroutines.delay, suspending; не блокирует тред
- Никаких ручных тредов / executor-ов / mutex-ов. Engine stateless (кроме final `Random`)
- Cancellation работает "бесплатно" — `delay()` и `emit()` cancellable. Никакого `runBlocking` / `Thread.sleep`

## Files/Areas

- `src/main/kotlin/com/faker/llm/engine/StreamingEngine.kt` — интерфейс
- `src/main/kotlin/com/faker/llm/engine/DefaultStreamingEngine.kt` — реализация
- `src/main/kotlin/com/faker/llm/engine/Chunking.kt` — helper `chunkByRange`
- `src/main/kotlin/com/faker/llm/engine/CallIdGenerator.kt` — генератор `callId` (24-char alphanumeric)

## Key Points

- Engine провайдер-агностичный. Никаких импортов из `adapter/*` или Ktor
- `delay()` — suspend, не `Thread.sleep`. Под 1000 RPS критично: 10k+ конкарентных стримов держатся на корутинах
- Mid-stream error прерывает Flow без `StreamEnd`. Адаптер при получении `StreamError` НЕ шлёт финальный фрейм (`[DONE]` / `message_stop`)
- `tool_name` берётся из request, не из entry. Если в request нет `tools` — selector не должен был пустить tool-call-entry
- `callId` важен для Anthropic-формата (`content_block_start`/`content_block_stop` используют один id). Генерим один на tool-call-part
- `kotlinx.coroutines.delay(Long)`, не `delay(Duration)` — фиксированный тип
- Чанкирование через `Sequence`, не `List<String>`. Под 1000 RPS allocation pressure имеет значение

## Done When

- [ ] `DefaultStreamingEngine.execute(entry, ctx)` возвращает корректный `Flow<AbstractStreamEvent>` для `SuccessEntry`
- [ ] Тайминги соблюдаются: TTFT перед первым чанком, inter-chunk delays между чанками — проверяется ручным запуском с логированием времени каждого эмита
- [ ] Tool-call часть корректно эмитит `ToolCallStart` → N×`ToolCallArgsChunk` → `ToolCallEnd`, имя берётся из `ctx.toolNames`
- [ ] Mid-stream error прерывает Flow без `StreamEnd` и эмитит `StreamError`
- [ ] При передаче `HttpErrorEntry` — бросается `IllegalArgumentException`
- [ ] Cancellation работает: при `cancelAndJoin` коллектора Flow корректно завершается (ручная проверка)
- [ ] Проект компилируется без warning-ов
- [ ] В пакете `engine` нет импортов из `io.ktor.*` или `adapter/*`
