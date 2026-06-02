# Code Review — faker-llm

Целевая репа: `/Users/tozarin/Documents/kot/faker` (Ktor 3.5.0 / Kotlin 2.3.21 LLM-faker для нагрузочных тестов).

Ревью разделено на 4 независимых слоя, каждый агент пишет отчёт в свой файл:

| Файл | Скоуп |
|---|---|
| `01-core-review.md` | domain / pool / routing / engine — ядро без Ktor |
| `02-adapters-wiring-review.md` | adapter/openai, adapter/anthropic, app/* (ErrorHandling, AppliedTiming, RequestTimer), Main.kt |
| `03-ops-review.md` | build.gradle.kts, gradle.properties, settings.gradle.kts, Dockerfile, docker-compose.yml, scripts/, loadtest/, logback.xml, pool JSON resources |
| `04-contract-docs-review.md` | README.md, docs/manual-checks.md, соответствие реализации `faker-contract.md` (контракт от внешней команды) |

## Формат каждого отчёта

```
# <Area> Review

## Summary (2-4 предложения)

## Findings
### 🔴 Critical
- **<file:line>** — описание + почему критично + предложение фикса
### 🟠 Major
- ...
### 🟡 Minor / nit
- ...
### 🟢 Strengths (что сделано хорошо)
- ...

## Open questions для оркестратора
- ...
```

Используй точные `file:line` цитаты. Не выдумывай — лучше «не проверено» чем галлюцинация.
