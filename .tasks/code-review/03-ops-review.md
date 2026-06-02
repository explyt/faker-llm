# Ops / Infrastructure Review

## Summary

Ops-слой собран по-взрослому: multi-stage Dockerfile с non-root + tini + healthcheck, compose с ulimits и restart policy, bash-скрипты со `set -euo pipefail` и портабельными приёмами, осмысленный logback с size+time rotation. Pool JSON-фикстуры консистентны, веса в default-пуле ровно складываются в 100, IDs уникальны внутри активного оверлея. Основные проблемы — рассинхрон между `loadtest/README.md` и реальными stage-сценариями, `scripts/loadtest.sh` физически не умеет запустить stage3-5, k6 не проверяет faker-specific хедеры/поля, и `shadowJar { mergeServiceFiles() }` нигде не задан явно (полагаемся на дефолты Ktor-плагина).

## Findings

### 🔴 Critical
_(не нашёл)_

### 🟠 Major

- **`loadtest/README.md:14-21`** — README говорит `Dkotlinx.coroutines.scheduler.max.pool.size=64`, но реальный prod-конфиг (см. memory `faker-llm-load-tuning` + комментарии в `loadtest/faker-load-stage2.js:10`, `stage3.js:9`, `Dockerfile:44`, `scripts/run.sh:23`) использует `=256`. Скопировавший команду из README не получит заявленных 1000 RPS — упрётся в scheduler. Фикс: обновить README до `=256` или поставить в конце явный «production tuning» блок.
- **`loadtest/README.md` (весь файл)** — описывает только `faker-load.js` (stage1). Не упомянуты `faker-load-stage2.js` (дефолт в `scripts/loadtest.sh`), `stage3/4/4b/5`. README расходится с реальным состоянием каталога. Фикс: добавить таблицу «какой stage за что отвечает» + актуальные SLO для каждого.
- **`scripts/loadtest.sh:17-22`** — `STAGE` принимает только `1|2`, остальные значения отбрасываются с `exit 2`. При этом в репозитории лежат `loadtest/faker-load-stage3.js`, `stage4.js`, `stage4b.js`, `stage5.js`. Фикс: расширить `case` до `3|4|4b|5`, либо чётко задокументировать, что это «архивные сценарии запуска руками через `k6 run`».
- **`loadtest/faker-load.js:51,63,80` и аналогично в `stage2..stage5`** — `check()` проверяет только `r.status === 200`. Задание ревью прямо просит верификацию `faker_elapsed_ms`, `X-Faker-Applied-Timing`, `X-Request-Id`. Сейчас сервер может тихо потерять instrumentation-хедеры или поле, и нагрузочный тест этого не заметит. Фикс: для streaming-кейса парсить хотя бы первый SSE frame и проверять `faker_elapsed_ms` + `r.headers['X-Faker-Applied-Timing']` для non-streaming.
- **`build.gradle.kts:1-55`** — нет явного `tasks.shadowJar { mergeServiceFiles(); archiveClassifier.set("all") }` блока. На практике `io.ktor.plugin` 3.5.0 при наличии `com.gradleup.shadow` сам настраивает `mergeServiceFiles`, и memory подтверждает что фат-джар работает на 1000 RPS — но это договорённость по умолчанию и не зафиксирована в проекте. Если кто-то обновит/уберёт ktor plugin, конфликтующие `META-INF/services/*` от Netty молча перезатрутся (last-wins) → runtime-падения в неочевидном месте. Фикс: добавить явный блок:
  ```kotlin
  tasks.shadowJar {
      archiveClassifier.set("all")
      mergeServiceFiles()
  }
  ```

### 🟡 Minor / nit

- **`gradle.properties:1`** — `# Faker LLM ? single source of truth ...` — em-dash побит кодировкой, виден `?`. Косметика.
- **`Dockerfile:12`** — `./gradlew --no-daemon --quiet help` не подтягивает compile-зависимости (task `help` ничего не резолвит за пределами плагинов). Кеш слоя «деп­ы» практически не работает — при любом изменении `src/` всё качается заново. Фикс: заменить на `./gradlew --no-daemon --quiet dependencies` (или `resolveAllDependencies` если есть), либо просто признать честно и убрать слой.
- **`docker-compose.yml:32-36`** — `deploy.resources.limits` исторически honored только в Swarm. Современный `docker compose` v2 (плагин) уважает его и в standalone, но legacy `docker-compose` v1 (Python) — нет. Хотим страховку — продублировать `mem_limit: 3g` на верхнем уровне сервиса (deprecated, но работает везде) или зафиксировать в README «требуется docker compose v2».
- **`docker-compose.yml`** — нет `cpus`/`cpu_count` в limits. На ноутбуке несущественно, но в общем кластере неограниченный CPU может сожрать соседей под бёрстом GC. Опционально.
- **`scripts/run-background.sh:36-43`** — 30-секундный health timeout без exponential backoff. Если JVM на холодной машине поднимается дольше (например, `cleanroom pain` cold start, GC tuning), скрипт пометит запуск как failed, но процесс продолжит жить. Фикс: на failure делать `kill "$PID"` перед `exit 1`.
- **`scripts/stop.sh:24-27`** — после `kill -9` нет финальной проверки `kill -0` — если процесс упорный (zombie или uninterruptible), мы говорим `[stop.sh] done`, хотя он жив. Фикс: после SIGKILL ещё раз проверить и при провале `exit 1`.
- **`.gitignore`** — не игнорится `.run/` (создаётся `scripts/run-background.sh` для pid+log). В `.dockerignore` он есть, в `.gitignore` нет. Фикс: добавить `.run/`.
- **`src/main/resources/logback.xml:32-42`** — `RollingFileAppender` без `AsyncAppender`-обёртки. На 1000 RPS CallLogging выдаёт ~1000 INFO/s — для logback ConsoleAppender+File это всё ещё ок (~1MB/s), но при росте нагрузки или увеличении verbosity станет hot spot. Опциональный апгрейд: завернуть FILE в `<appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">` с queueSize=4096.
- **`src/main/resources/pool/08-http-errors.json:44`** — `http-504-timeout-01` держит соединение `preResponseDelayMs: 5000-15000` до отдачи 504. При 5% инжекции на 700 streaming RPS это в среднем ~35 «висящих» соединений ×10s = ~350 паразитных live-сокетов. Похоже, это by design (хотим эмулировать реальный таймаут up-stream), но стоит зафиксировать в комментарии в самом JSON или в README пулов, чтобы не было сюрпризов при анализе TCP TIME_WAIT.
- **`src/main/resources/pool-clean/*` vs `src/main/resources/pool/*`** — `pool-clean` переиспользует те же `id` (`short-greeting-01`, `tool-empty-args-01`, ...), что и default pool. `pool-deepseek` уже честно префиксует `ds-`. Не баг (одновременно активен только один overlay), но в логах/observability двойная сущность с одинаковым id из двух разных пулов — ловушка для будущего «а почему latency у `short-ack-02` так гуляет между релизами». Лучше унифицировать: `clean-` prefix в `pool-clean/`.
- **`src/main/resources/pool-deepseek/`** — нет файлов `07-mid-stream-errors.json` / `08-http-errors.json`. Default pool их содержит. Скорее всего это и есть «чистый стресс-профиль без ошибок», но в файлах оверлея нет ни комментария, ни README, объясняющего отсутствие. Хорошо бы `src/main/resources/pool-deepseek/README.md` или хотя бы запись в `loadtest/README.md`.
- **`Dockerfile:54-55`** — `CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/faker-llm-all.jar"]` корректно делает `exec` чтобы JVM получила PID 2 под tini. Это уже правильно, но shellcheck SC2086 пожалуется на `$JAVA_OPTS` без кавычек — комментарий рядом про word-splitting спасает читателя, но не статический анализ. Косметика.
- **`scripts/loadtest.sh:33`** — `--summary-trend-stats='min,med,p(90),p(95),p(99),max'` забит хардкодом. Не вижу способа переопределить через env. Опционально: `K6_SUMMARY_TREND_STATS` или флаг.
- **`docker-compose.yml:9`** — `"${PORT:-8080}:8080"` фиксирует внутренний порт 8080 даже когда внешний меняется. Внутрь контейнера env прокидывает `PORT=8080` неявно (через ENV в Dockerfile). Корректно, но если кто-то задаст `PORT=9090` в `.env`, наружу пойдёт 9090, а Ktor внутри слушает 8080 — связка останется работающей только благодаря публикации. Стоит документировать.
- **`loadtest/faker-load.js` и др.** — все 6 файлов на 90% копипаста. Можно вынести в `loadtest/_common.js` через `import {streamingRequest,...} from './_common.js'`. Опционально.

### 🟢 Strengths

- **`Dockerfile:5-22`** — честный multi-stage build (JDK для сборки, JRE для рантайма), кешируемый слой `gradlew --no-daemon help`, tini в качестве init для корректного SIGTERM, non-root user `faker:1000`, HEALTHCHECK через curl.
- **`docker-compose.yml`** — есть всё, что должно быть: healthcheck, restart policy, ulimits.nofile, named volume под логи, memory limit, JVM tuning через MaxRAMPercentage. Не «hello world», а прод-поза.
- **`scripts/*.sh`** — единообразный `#!/usr/bin/env bash` + `set -euo pipefail` + `cd "$(dirname "$0")/.."`. Quoting корректный, `docker.sh` грамотно ловит `docker compose` vs legacy `docker-compose`. `smoke.sh` использует портабельный `sed '$d'` вместо GNU-only `head -n-1`.
- **`scripts/smoke.sh`** — пять чек-боксов (healthz, openai non-stream, anthropic non-stream, force_status:429, streaming [DONE]) — достаточный smoke без перегруза.
- **`gradle.properties`** — единая точка правды для версий, settings.gradle.kts корректно читает через `val ... by settings`, build.gradle.kts — через `val ... by project`. Версии (Kotlin 2.3.21, Ktor 3.5.0, Shadow 9.4.2, foojay 1.0.0) совпадают с memory `faker-llm-stack`.
- **`.gitignore:31-32`** — есть негация `!gradle/wrapper/gradle-wrapper.jar` поверх общего `*.jar` rule. Memory-quirk зафиксирован корректно.
- **`src/main/resources/logback.xml`** — `SizeAndTimeBasedRollingPolicy` (50MB/файл, 7 дней, 500MB cap, gzip), осознанный отказ от ANSI strip в pattern (XML 1.0 не разрешает `&#x1b;` — комментарий это объясняет), `LOG_DIR` override через JVM/env.
- **Pool JSON weights** — default `pool/` ровно складывается в 100 (25+25+20+5+5+10+5+5 = 100, совпадает с заявленными в memory %). Все weights положительны, у всех entries есть `tag`, `id` уникальны внутри активного оверлея. `tool_call` entries имеют `requiresTools: true` + `finishReason: "ToolCalls"`. `mid_stream_error` entries имеют `midStreamError` с `afterChunks`/`kind`. `http_error` имеют `status`/`errorBody`/`preResponseDelayMs`. Контракт выдержан.
- **`loadtest/faker-load-stage2.js:1-9`** — внятный header-комментарий с обоснованием изменений vs stage1. Аналогично stage3/4/5 — каждый файл документирует «почему именно эти числа». Это редкость и большой плюс.
- **`Dockerfile:42-45`** + **`docker-compose.yml:19`** — JVM tuning согласован (`-XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Dkotlinx.coroutines.scheduler.max.pool.size=256`), от заявленного prod-конфига отступает только `MaxRAMPercentage=75` вместо жёсткого `-Xmx2g` — что разумно для контейнеризации.

## Open questions для оркестратора

- **`shadowJar { mergeServiceFiles() }`** — действительно ли `io.ktor.plugin` 3.5.0 настраивает merge автоматически? Если да, можно ограничиться записью в README/комментарием. Если нет (или версия плагина изменится) — это надо добавить явно. Стоит проверить байтами в `build/libs/faker-llm-all.jar`: `unzip -p faker-llm-all.jar META-INF/services/io.netty.resolver.dns.DnsServerAddressStreamProvider` должно отдать содержимое.
- **k6 thresholds** — нужно ли расширить SLO до проверки `dropped_iterations < 1%`? Сейчас этот контр-метрик не порождает failure threshold, и stage4/5 могут проходить с большой долей дропов на стороне генератора.
- **`scripts/loadtest.sh` STAGE поддержка** — добавлять `3|4|4b|5` или эти файлы признаются «исследовательскими» и переезжают в `loadtest/archive/`?
- **pool overlays для load-tuning** — где хранится решение «какой overlay использовать в каком сценарии» (default vs deepseek vs short-only)? В `loadtest/README.md` сейчас этого нет.
