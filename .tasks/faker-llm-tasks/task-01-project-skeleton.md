# Task 01: Project Skeleton

**Type:** Code Modification

## Goal

Поднять Gradle Kotlin DSL проект со всеми нужными плагинами и зависимостями, чтобы последующие задачи могли компилироваться и запускаться без переделок инфраструктуры.

## What to Do

- Создать `build.gradle.kts` с плагинами: `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.serialization`, `io.ktor.plugin` (Ktor application plugin), `com.gradleup.shadow` (или `com.github.johnrengelman.shadow` если новее не подходит)
- Создать `settings.gradle.kts` с именем `faker-llm` и pluginManagement
- Создать `gradle.properties` (Kotlin/Ktor/JDK версии — последние стабильные на момент исполнения; JDK 21)
- Подключить зависимости:
  - Server: `ktor-server-core`, `ktor-server-netty`, `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-server-call-logging`, `ktor-server-status-pages`, `ktor-server-sse` (если есть в текущей версии Ktor) или ручная SSE-запись через `respondTextWriter`
  - Сериализация: `kotlinx-serialization-json`
  - Логи: `logback-classic`
  - Тесты: `ktor-server-test-host`, `kotlin-test-junit5`, `kotlinx-coroutines-test`
- Зафиксировать `jvmToolchain(21)` и `application { mainClass.set("com.faker.llm.MainKt") }`
- Создать skeleton-пакетов под `src/main/kotlin/com/faker/llm/`:
  - `domain/` — модели пула и стрим-событий
  - `pool/` — загрузка и выбор entry
  - `engine/` — стриминг-движок
  - `adapter/openai/` — OpenAI-формат
  - `adapter/anthropic/` — Anthropic-формат
  - `app/` — точка входа и Ktor-модули
- Создать `src/main/kotlin/com/faker/llm/Main.kt` со stub `fun main()` (просто логирует старт — реальное подключение Ktor будет в Task 08)
- Создать `src/main/resources/logback.xml` с простым ConsoleAppender (паттерн `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`)
- Создать пустой `src/main/resources/application.conf` (полноценное наполнение в Task 08)
- Создать `.gitignore` под Gradle/Kotlin/IDEA

## Files/Areas

- `build.gradle.kts` — главный билд-скрипт
- `settings.gradle.kts` — root project name
- `gradle.properties` — версии
- `src/main/kotlin/com/faker/llm/Main.kt` — stub entrypoint
- `src/main/resources/logback.xml` — лог-конфиг
- `src/main/resources/application.conf` — заглушка под Ktor конфиг
- `.gitignore`

## Key Points

- Ktor — последняя стабильная (3.x), Kotlin — последний стабильный (2.x). Если что-то конфликтует — синхронизировать по `ktor-plugin`.
- Shadow plugin нужен, чтобы был fat jar (`shadowJar` task) — это запасной путь деплоя.
- Никакой бизнес-логики на этом этапе не пишем. Цель — чтобы `./gradlew build` и `./gradlew run` отрабатывали.
- Wrapper создаётся через `gradle wrapper --gradle-version <stable>` руками (вне file edit scope) — агент должен это сделать через `run_command`.

## Done When

- [ ] `./gradlew build` проходит без ошибок и warning-ов по unresolved-зависимостям
- [ ] `./gradlew run` стартует, логирует "starting" и завершается (или висит, если stub так задумано — допустимо)
- [ ] `./gradlew shadowJar` создаёт fat jar в `build/libs/`
- [ ] Все указанные пакеты существуют (могут быть пустыми с `package-info`-аналогом или просто как директории)
- [ ] `logback.xml` подключён, в выводе видны лог-строки от Ktor/JVM
- [ ] `.gitignore` исключает `.gradle/`, `build/`, `.idea/`, `*.iml`
