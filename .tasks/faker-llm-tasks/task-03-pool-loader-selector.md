# Task 03: Pool Loader & Selector

**Type:** Code Modification

## Goal

Реализовать загрузку pool entries из JSON-ресурсов classpath и выбор entry под конкретный запрос: фильтрация по применимости (tools / non-tools) + применение routing decision + weighted random pick. Это input bus в ядро, дальше дёргается engine.

## What to Do

### Loader

- В `com.faker.llm.pool` создать `PoolLoader`:
  - `fun load(directory: String = "pool"): List<PoolEntry>` — читает все `*.json` из classpath-директории (по дефолту `pool`), парсит kotlinx.serialization JSON с polymorphic-конфигом из Task 02
- Один файл может содержать либо один `PoolEntry`, либо JSON-массив `[PoolEntry, ...]` — поддерживаем оба варианта
- При парсинге каждой entry валидировать `weight > 0`; если нет — лог-варн и skip с указанием id и файла
- Логировать на старте: сколько файлов прочитано, сколько entries загружено, сколько отфильтровано как невалидные

### Selector

- `class PoolSelector(private val entries: List<PoolEntry>, private val random: Random = Random.Default)`:
  - `fun pick(ctx: RequestContext, decision: RoutingDecision = RoutingDecision.Default): PoolEntry` — сначала фильтрует по `decision`, затем по `applicableTo(ctx)`, выбирает по весу
  - Бросает `EmptyPoolException` если после фильтрации ничего не осталось — в мессадже указать, какой decision/ctx привёл к пустому набору
- Применение `RoutingDecision`:
  - `Default` — фильтр не накладывается
  - `ForceEntryId(id)` — оставляем только entry с `entry.id == id`
  - `RequireTag(tag)` — оставляем entries с `entry.tag == tag`
  - `ForceHttpStatus(status)` — оставляем `HttpErrorEntry` с `status == ...`
- Правила применимости (`applicableTo`):
  - `entry.requiresTools = true` и `ctx.hasTools = false` → entry **не применим**
  - `entry.requiresTools = false` → entry применим в любом случае
  - HTTP error entries применимы всегда
- Weighted random pick: prefix-sum + бинарный поиск, либо простой линейный walk по применимым (n маленькое — без оверинжиниринга)
- `Random` инжектится через конструктор; в продакшене используется `Random.Default`

## Files/Areas

- `src/main/kotlin/com/faker/llm/pool/PoolLoader.kt`
- `src/main/kotlin/com/faker/llm/pool/PoolSelector.kt`
- `src/main/kotlin/com/faker/llm/pool/EmptyPoolException.kt`
- `src/main/kotlin/com/faker/llm/pool/PoolJson.kt` — `object` с настроенным `Json` инстансом и polymorphic `SerializersModule`, переиспользуется loader-ом и адаптерами

## Key Points

- Загрузка происходит **один раз на старте**, не на каждый запрос. `PoolSelector` хранит уже распарсенный `List<PoolEntry>`.
- Polymorphic JSON-конфиг: `Json { classDiscriminator = "kind"; ignoreUnknownKeys = true; serializersModule = ... }` — описать в `PoolJson`, переиспользовать везде.
- HTTP error entries — часть **того же пула**, что и success. Не выделены в отдельную коллекцию. Это даёт естественное "X% запросов получают 429, Y% получают 500" через веса.
- Фильтрация `requiresTools` происходит **только для tool-call success-entries**. Если у success-entry нет ни одного `ToolCall` в `parts`, `requiresTools` обязан быть `false` — валидируем при загрузке (warn + skip).
- Не делать "fallback на любой entry" если selector ничего не нашёл. Лучше упасть и громко прокричать в лог.

## Done When

- [ ] `PoolLoader.load()` загружает single-entry и array-файлы из classpath (проверяется вручную через демо-вызов в `main` или временный лог-вывод; НЕ юнит-тестом)
- [ ] `PoolSelector.pick(ctx, decision)` корректно применяет все 4 варианта `RoutingDecision`
- [ ] `PoolSelector.pick()` фильтрует `requiresTools` entries при `hasTools = false`
- [ ] Weighted random pick реализован; `EmptyPoolException` бросается при пустом после-фильтрационном списке
- [ ] Невалидные entries (weight ≤ 0, requiresTools=true без ToolCall в parts) логируются как warn и игнорируются
- [ ] Проект компилируется
