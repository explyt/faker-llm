# Task 04: Request Router

**Type:** Code Modification

## Goal

Создать промежуточный слой между адаптерами и pool selector. Router смотрит на user-prompt контент, ищет спец-маркеры внутри текста и решает: форсить ли конкретный сценарий (entry / тег / HTTP-статус) или пустить стандартный weighted pick. Никакой HTTP-обвязки — всё через сам промпт.

## What to Do

### Routing decision

В новом пакете `com.faker.llm.routing`:

- `sealed interface RoutingDecision`:
  - `data object Default : RoutingDecision`
  - `data class ForceEntryId(val id: String) : RoutingDecision`
  - `data class RequireTag(val tag: String) : RoutingDecision`
  - `data class ForceHttpStatus(val status: Int) : RoutingDecision`

### Router contract

- `fun interface RequestRouter { fun route(ctx: RequestContext): RoutingDecision }`
- Использует только `ctx.inspectableContent` (из обновлённого Task 02 — concat user-message контента)

### Default implementation

- `class CompositeRequestRouter(private val policies: List<RoutingPolicy>) : RequestRouter`
- `fun interface RoutingPolicy { fun decide(ctx: RequestContext): RoutingDecision? }` — `null` = pass-through
- Идём по списку, первый ненулевой результат побеждает; все `null` → `Default`

Абстракция оставлена сознательно (на будущее: model-based, randomized policies и т.п.), но в первой итерации регистрируется только одна.

### Стартовая policy

`PromptDirectivePolicy` — substring-поиск маркеров в `ctx.inspectableContent`:
- `[[faker:force_id:<id>]]` → `ForceEntryId`
- `[[faker:force_tag:<tag>]]` → `RequireTag`
- `[[faker:force_status:<n>]]` → `ForceHttpStatus`

Префикс `[[faker:` и суффикс `]]` — **хардкод-константы** в файле `PromptDirectivePolicy.kt` (`private const val DIRECTIVE_PREFIX = "[[faker:"` / `DIRECTIVE_SUFFIX = "]]"`). Менять их через config не нужно.

Поиск регистронезависимый. При нескольких маркерах — побеждает первый по позиции. Если ни одного — `null`.

## Files/Areas

- `src/main/kotlin/com/faker/llm/routing/RoutingDecision.kt`
- `src/main/kotlin/com/faker/llm/routing/RequestRouter.kt`
- `src/main/kotlin/com/faker/llm/routing/RoutingPolicy.kt`
- `src/main/kotlin/com/faker/llm/routing/CompositeRequestRouter.kt`
- `src/main/kotlin/com/faker/llm/routing/policies/PromptDirectivePolicy.kt`

## Key Points

- Router работает **только** с `RequestContext.inspectableContent`. Никаких header / URL params — это сознательное упрощение.
- Адаптер собирает `inspectableContent` провайдер-специфично: concat user-message контента из `messages[]` (OpenAI/Anthropic). Один раз на запрос.
- Substring-поиск дешёвый, для 1000 RPS не bottleneck. Если Task 11 покажет проблему — заменим на pre-compiled regex; пока KISS.
- Policy-интерфейс остаётся даже с одной policy: расширение — drop-in без перетряхивания CompositeRequestRouter.
- Router stateless — нативно живёт под высокой конкарентностью.
- Если в промпте указан несуществующий `force_id` или `force_status` без соответствующих entries в пуле — `PoolSelector` бросает `EmptyPoolException`, фейкер отдаёт 500. Это feature: клиент увидит свою опечатку.
- НЕТ HOCON-конфига для router. Все поведенческие константы — хардкод в коде.

## Done When

- [ ] `RoutingDecision`, `RequestRouter`, `RoutingPolicy`, `CompositeRequestRouter`, `PromptDirectivePolicy` созданы
- [ ] `PoolSelector.pick(ctx, decision)` (из Task 03) корректно применяет все 4 варианта `RoutingDecision`
- [ ] Ручная проверка (демо-вызов в `main` или временный лог): 4 сценария — `Default` (нет маркеров), `[[faker:force_status:429]]`, `[[faker:force_tag:tool_call]]`, `[[faker:force_id:<id>]]` — выдают ожидаемые decisions; после проверки временный код убрать
- [ ] Проект компилируется без warning-ов
- [ ] В пакете `routing` нет импортов из `io.ktor.*` или `com.faker.llm.adapter.*`
