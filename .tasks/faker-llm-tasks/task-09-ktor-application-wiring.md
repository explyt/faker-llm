# Task 09: Ktor Application Wiring

**Type:** Code Modification

## Goal

Связать всё воедино: Ktor Application + Netty engine, `application.conf` (минимальный), DI всех компонентов (PoolLoader → PoolSelector → RequestRouter → StreamingEngine + адаптеры), регистрация route-ов под обоими endpoint-ами, glue для cancellation / error handling.

## What to Do

### `application.conf` (минимальный)

```hocon
ktor {
  deployment {
    port = 8080
    port = ${?PORT}
  }
  application {
    modules = [ com.faker.llm.MainKt.module ]
  }
}
```

Никаких faker-специфичных секций.

### `Main.kt`

- `fun main(args: Array<String>) = EngineMain.main(args)` — стандартная Ktor-конвенция
- `fun Application.module() { ... }` — основной module

### `Application.module()`

1. Инстанцировать компоненты (local `val`-ы, без DI-фреймворка):
   - `val poolEntries = PoolLoader().load("pool")`
   - `val poolSelector = PoolSelector(poolEntries)`
   - `val router: RequestRouter = CompositeRequestRouter(listOf(PromptDirectivePolicy()))`
   - `val streamingEngine: StreamingEngine = DefaultStreamingEngine()`
2. Ktor-плагины:
   - `ContentNegotiation { json(PoolJson.json) }` — переиспользуем настроенный `Json` инстанс
   - `CallLogging` — `Level.INFO`, **без body logging** (1000 RPS = gregged лог)
   - `StatusPages`:
     - `exception<EmptyPoolException>` → 500 с `{"error": {"type": "pool_misconfigured", "message": "..."}}`
     - `exception<SerializationException>` → 400 с `{"error": {"type": "invalid_request", "message": "..."}}`
     - `exception<Throwable>` (catch-all) → 500
3. Routes: `routing { healthRoute(); openAiRoutes(...); anthropicRoutes(...) }`

### Cancellation / connection lifecycle

- Ktor + Netty уже отменяет coroutine при закрытии TCP-соединения. Никакой ручной обвязки
- В адаптерах при стриминге использовать `withContext(call.coroutineContext)` для корректного cancellation Flow

### Health endpoint

- `GET /healthz` → `200 OK` text/plain `"ok"`. Полезно для k8s liveness, k6/gatling pre-check

### Логи на старте

- В `module()`: `log.info("Faker LLM starting: ${poolEntries.size} pool entries loaded")`
- Один лог на запрос (`CallLogging`) — без тел, только method/path/status/duration

## Files/Areas

- `src/main/resources/application.conf` — обновить (был stub из Task 01)
- `src/main/kotlin/com/faker/llm/Main.kt` — заменить stub из Task 01
- `src/main/kotlin/com/faker/llm/app/HealthRoute.kt` — `Route.healthRoute()` extension

## Key Points

- НЕ использовать DI-фреймворк (Koin / Dagger). На 1000 RPS overhead резолва не нужен, и без него меньше движущихся частей
- `EngineMain.main(args)` (вместо `embeddedServer { ... }.start()`) — даёт декларативный `application.conf`. Стандарт production-Ktor
- Call-body logging **выключить**. На нагрузке это смерть от диска и GC
- `StatusPages` обязателен — без него Ktor дефолтит на HTML-500, а нам нужен JSON
- `PoolJson.json` (из Task 03) переиспользуется в `ContentNegotiation` — один настроенный инстанс на проект
- HTTPS не настраиваем. Faker за reverse-proxy или прямой HTTP
- Аутентификация не настраивается. Принимаем любой `Authorization` или его отсутствие

## Done When

- [ ] `./gradlew run` стартует приложение, слушает порт 8080 (или `$PORT`)
- [ ] Лог старта показывает количество загруженных pool entries
- [ ] `GET /healthz` отвечает `200 ok`
- [ ] `POST /v1/chat/completions` и `POST /v1/messages` доступны и не падают на минимальном валидном запросе
- [ ] `EmptyPoolException` (например при `[[faker:force_id:nonexistent]]`) превращается в JSON-500 с `pool_misconfigured`
- [ ] Невалидный JSON в body превращается в 400 с `invalid_request`
- [ ] Лог запроса — одна строка с method/path/status/duration, без body
- [ ] Закрытие соединения клиентом во время стрима — Flow в engine отменяется (`curl --max-time 1 ... | head` чисто завершается без зависших корутин)
