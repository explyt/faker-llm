package com.faker.llm.domain

import kotlinx.serialization.Serializable

/**
 * Parsed shape of the `X-Faker-Directive` JSON header, as defined in `faker-contract 2.md`.
 *
 * The contract is a discriminated ADT: the [type] picks the variant and only that variant's
 * sub-object is expected to be populated. Supported values: `normal`, `thinking`, `tool_call`,
 * `empty`, `error`, `timeout`. Unknown values are tolerated at parse time (pass-through to the
 * pool) but won't be honored as a contract behaviour.
 *
 * Fields removed in v2 of the contract and intentionally NOT modeled here: `tokens.output`
 * (length is now derived from timing), `seed`, `error.code`, `error.message`,
 * `tool_call.args_keys`. Unknown JSON fields are silently ignored by the parser
 * (`ignoreUnknownKeys = true`), so older clients still sending them won't break.
 */
@Serializable
data class FakerDirective(
    val type: String,
    val error: FakerDirectiveError? = null,
    val thinking: FakerDirectiveThinking? = null,
    val tool_call: FakerDirectiveToolCall? = null,
    val timing: FakerDirectiveTiming? = null,
    val replay: FakerDirectiveReplay? = null,
)

/**
 * `replay` sub-object: carries the base64url(no-pad)-encoded recorded assistant message the
 * load client asks Faker to echo verbatim (type `replay`). The decoded JSON has `content`,
 * `tool_calls[].{name,arguments}` and `finish_reason`; [com.faker.llm.engine.SyntheticEntryBuilder]
 * decodes [payload] and rebuilds the response so the client can verify chain integrity.
 */
@Serializable
data class FakerDirectiveReplay(
    val payload: String? = null,
)

/**
 * `error` sub-object from the directive. Per v2 of the contract only `http_status` is part
 * of the wire shape — `code`/`type`/`message` text is fully owned by the faker.
 *
 * Field name `http_status` mirrors the contract verbatim (snake_case from the JSON spec).
 */
@Serializable
data class FakerDirectiveError(
    val http_status: Int? = null,
)

/** `thinking` sub-object: min_tokens controls the minimum reasoning prefix length. */
@Serializable
data class FakerDirectiveThinking(
    val min_tokens: Int? = null,
)

/**
 * `tool_call` sub-object: only the tool name remains in v2 of the contract.
 * Engine reads the tool name from [RequestContext.toolNames] — the route handler overrides
 * `ctx.toolNames = [name]` before invoking the engine.
 */
@Serializable
data class FakerDirectiveToolCall(
    val name: String? = null,
)

/**
 * `timing` sub-object: ttft / inter-token-latency / total-target overrides.
 * `total_ms` drives the response length via `round((total_ms − ttft_ms) / itl_ms) + 1`.
 */
@Serializable
data class FakerDirectiveTiming(
    val ttft_ms: Long? = null,
    val itl_ms: Long? = null,
    val total_ms: Long? = null,
)
