# Core (domain / pool / routing / engine) Review

## Summary

Ядро faker-llm выглядит крепко: hexagonal-инвариант "никакого `io.ktor.*` / `adapter.*` в core" соблюдён (проверено grep'ом), cold flow streaming с suspend `delay()`, корректная mid-stream error семантика, sane discriminator-стратегия в `PoolJson`. Critical багов не нашёл. Есть один **major-контрактный пробел** (`FakerDirective.total_ms` объявлено, но нигде не читается) и один **major-валидационный пробел** (`PoolLoader` не валидирует `chunkSizeChars.min > 0`, runtime-крах в hot path). Остальное — minor / nits, в основном чистота кода и documentation drift. По skill issue — не похоже, ребята явно знают, что делают.

## Findings

### 🔴 Critical
_Нет._

### 🟠 Major

- **`domain/FakerDirective.kt:59` + `engine/SyntheticEntryBuilder.kt:96-105`** — `FakerDirectiveTiming.total_ms` объявлено и парсится, но **нигде не читается**. `timingFromDirective(...)` использует только `ttft_ms` и `itl_ms`. Комментарий в `FakerDirective.kt:8` явно утверждает: "Fields modeled here cover everything except `tokens` and `seed` (not implemented)" — то есть `total_ms` числится среди реализованных, но эффекта не имеет. Это контрактный gap: клиент, шлющий `{"type":"slow","timing":{"total_ms":5000}}`, ожидает суммарную длительность ~5s, по факту получает дефолтный темп. Либо реализовать (например, для `slow`: разложить `total_ms` на чанки и подстроить `itl_ms`), либо удалить поле и пометить как "not implemented" в KDoc.

- **`pool/PoolLoader.kt:82` (`validate`) + `engine/Chunking.kt:14`** — Loader не проверяет `TimingProfile.chunkSizeChars.min > 0`. Если в pool JSON указан `"chunkSizeChars": {"min": 0, "max": 32}`, parse проходит, entry попадает в pool, но при **первом же запросе на эту entry** `chunkByRange` бросит `IllegalArgumentException("chunk size lower bound must be > 0, was 0")`. Это runtime-крах в hot path под нагрузкой, не отловимый до тех пор, пока weighted-random не выберет именно эту entry. Аналогично стоит проверить `ttftMs.min >= 0`, `interChunkMs.min >= 0` и `chunkSizeChars.max >= min` (хотя `randomIn` устойчив к инверсии, `chunkByRange` использует `range.first..range.last` напрямую). Фикс: добавить ветки в `PoolLoader.validate(...)`.

### 🟡 Minor / nit

- **`engine/DefaultStreamingEngine.kt:152`** (IDE inspection `UnusedVariable`) — в `emitToolCall` цикл `for ((index, delta) in argsJson.chunkByRange(...).withIndex())` использует только `delta`, `index` не нужен (в tool-call мы дилеим даже на chunk #0). Заменить на `for (delta in argsJson.chunkByRange(chunkRange, random))`.

- **`domain/TimingProfile.kt:27`** (IDE inspection `UnusedSymbol`) — `RangeInt.randomIn()` (без `random`-параметра) не вызывается нигде. Парная `RangeMs.randomIn()` (line 18) используется в адаптерах (`OpenAiRoutes.kt:162`, `AnthropicRoutes.kt:168` через `entry.preResponseDelayMs.randomIn()`), так что её удалять нельзя; `RangeInt`-overload же — мёртвый код. Удалить ИЛИ оставить как симметричное API (на ваш вкус, я бы убрал — YAGNI).

- **`engine/DefaultStreamingEngine.kt:117`** — между двумя соседними `parts` (например, `Thinking` → `Text`) **нет `interChunkMs` delay'а**: `if (index > 0) delay(...)` локален к одному part'у, при переходе к следующему `index` обнуляется и первый chunk нового part'а эмитится сразу за последним chunk'ом предыдущего. Если это intentional — стоит зафиксировать в memory/KDoc; если нет — добавить inter-part delay (или единый монотонный счётчик чанков across parts для пейсинга). Memory `faker-llm-streaming-engine` про переход между parts молчит, так что это серая зона — uncertain, надо подтвердить у владельца контракта.

- **`engine/DefaultStreamingEngine.kt:60,72,86`** — лямбда `onChunkEmitted = { delta -> emittedChunks++; completionChars += delta.length }` дублируется три раза идентичным телом. Вынести в один локальный `val` и передавать единственный экземпляр. Это и читабельность, и микро-аллокации (3 closure-объекта на entry vs 1).

- **`engine/SyntheticEntryBuilder.kt:79`** — для synthetic `tool_call` ставится `requiresTools = true`, но synthetic-entry минует `PoolSelector` (`SyntheticBehavior` уходит в route handler напрямую), так что поле никогда не читается. Не баг, но mental-model gotcha — `requiresTools` имеет смысл только для pool-loaded entries. Можно поставить `false` для honesty или добавить comment.

- **`pool/PoolLoader.kt:68`** — `logger.warn("Failed to parse pool file '{}': {} — skipped", fileName, e.message)`. `e.message` может быть null (например, у некоторых `JsonDecodingException`), в логе будет красивое "null — skipped". Сделать `e.message ?: e.javaClass.simpleName`.

- **`pool/PoolSelector.kt:14-20`** — KDoc говорит "Holds an already-parsed, immutable entry list", но `List<PoolEntry>` это контракт чтения, а не immutability. Если кто-то снаружи передаст `mutableListOf` и потом его мутирует — `PoolSelector` это не заметит. Можно `private val entries: List<PoolEntry> = entries.toList()` в конструкторе. Очень minor.

- **`pool/PoolSelector.kt:60-69`** — `weightedPick` упадёт с `IllegalArgumentException` если кто-то построит `PoolSelector` с entries, у которых `weight <= 0` (`random.nextDouble(0.0)` бросает). В production это защищено валидацией в `PoolLoader.validate`, но если в тестах руками собрать selector — будет неинформативная ошибка. Можно `require(totalWeight > 0)` с осмысленным сообщением.

- **`routing/policies/PromptDirectivePolicy.kt:43-55`** — `parseBody` корректно обрабатывает пустой `value` и пустой `key`. Edge: вложенные/перекрывающиеся маркеры типа `[[faker:force_id:hello [[faker:force_tag:foo]]` найдут "]]" внутри значения и заберут `value = "hello [[faker:force_tag:foo"`. Не баг (мусорный id просто не матчнётся в селекторе → `EmptyPoolException`), но nei-рецепт edge case'а: документация не оговаривает, что директивы не могут содержать `]]` в значении. Можно упомянуть в KDoc.

- **`pool/PoolLoader.kt:108-113`** (`readFromJar`) — комментарий ссылается на "shared (cached) JarFile". Это работает, ТОЛЬКО пока `JarURLConnection.getUseCaches()` возвращает `true` (default). Если кто-то в Main выкрутит `URLConnection.setDefaultUseCaches(false)` для каких-то целей — мы получим resource leak (открытый jar никогда не закрывается). На практике риска нет, но защита нулевая. Заметка, не действие.

- **Thread-safety инжектируемого `Random`** — `PoolSelector` и `DefaultStreamingEngine` принимают `Random` параметром. `Random.Default` thread-safe (под капотом `ThreadLocalRandom`), и под нагрузкой в 1000 RPS всё ОК. Но если кто-то инжектит обычный `Random(seed)` для детерминированных тестов и **этот же инстанс** делит между корутинами — race condition. Решается либо передачей `Random.Default` в проде (уже так), либо `ThreadLocalRandom` обёрткой. Стоит явно зафиксировать в KDoc, что инжектированный non-default `Random` для concurrent-use не безопасен.

### 🟢 Strengths

- **Архитектурный инвариант соблюдён**: ни одного импорта `io.ktor.*` или `com.faker.llm.adapter.*` в `domain`/`pool`/`routing`/`engine` (grep пуст по всем 4 пакетам ✕ 2 паттерна).
- **`PoolJson.kt:17`** — корректно НЕ переопределяет глобальный `classDiscriminator`, KDoc-комментарий явно объясняет почему (избежать конфликта с `ResponsePart`-discriminator `"type"`). Соответствие memory `faker-llm-serialization-contract`.
- **`PoolEntry.kt:24-26`** — `@OptIn(ExperimentalSerializationApi::class)` + `@JsonClassDiscriminator("kind")` + `@SerialName` на наследниках — ровно как в memory baseline.
- **`DefaultStreamingEngine`** — cold flow, suspending `delay()`, никаких `Thread.sleep`/`runBlocking`, корректное завершение БЕЗ `StreamEnd` при mid-stream error (line 124), корректный pre-check для `afterChunks == 0` (line 46-49). Соответствие memory `faker-llm-streaming-engine` полное.
- **`PoolSelector.weightedPick`** — линейный walk с floating-point safety net (`return candidates.last()`), KISS-комментарий объясняет выбор.
- **`PoolSelector.matches`** — `SyntheticHttpError`/`SyntheticBehavior` → `error(...)` с информативным сообщением. Fail loudly соблюдается.
- **`PromptDirectivePolicy.decide`** — `ignoreCase = true` без `lowercase()` сохраняет позиции для оригинального string'а, объяснено в комментарии (line 28).
- **`HeaderDirectivePolicy.decide`** — `runCatching` + WARN-log на битом JSON, fall-through к следующей policy. Tolerant и резистентен к middlebox'ам.
- **`Range*.randomIn`** — `if (min >= max) min else random.nextLong(min, max + 1)` — корректно ловит вырожденный и инвертированный случаи без throwing. Соответствие memory.
- **`CallIdGenerator`** — `call_` + 24 alnum, инжектируемый `Random`, без cryptographic-претензий (правильно для fake).
- **`Chunking.chunkByRange`** — Sequence вместо List (экономия аллокаций под 1000 RPS), `range.first > 0` `require` — единственная защита, но это есть.
- **`EmptyPoolException`** — диагностический message с `decision=$decision, hasTools=$ctx.hasTools (N total → M after decision → 0 applicable)` — отличная observability на проде.
- **Magic numbers** все вынесены в `companion object` (`RATE_LIMIT_STATUS`, `ID_LENGTH`, `THINKING_CHARS_PER_TOKEN`, дефолты `SyntheticEntryBuilder`).
- **KDoc'и обстоятельные** — почти на каждом public-классе с пояснением "почему так", а не только "что делает". Полезно при ревью под нагрузкой.

## Open questions для оркестратора

- **`FakerDirectiveTiming.total_ms`**: реализовать или удалить? Нужно сверить с `faker-contract.md` (вне моего скоупа — это файл из task 04). Если контракт требует — это критично, апаем до Critical.
- **Inter-part delay** между `Thinking` → `Text` (или любыми двумя соседними parts): intended или баг? Memory `faker-llm-streaming-engine` не уточняет. Стоит запросить у владельца контракта или проверить в адаптерах (вне core), как они себя ведут на `parts: [Thinking, Text]`.
- **`Random` thread-safety** для нестандартных инжекций — нужна ли явная защита, или достаточно KDoc-предупреждения? Если в `app/Main.kt` всегда `Random.Default` — fine, но это надо сверить (вне core-скоупа).
