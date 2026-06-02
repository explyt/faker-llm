package com.faker.llm.domain

import kotlinx.serialization.Serializable

/**
 * Parsed shape of the `X-Faker-Directive` JSON header, as defined in `faker-contract.md`.
 *
 * Fields modeled here cover everything except `tokens` and `seed` (not implemented).
 * Unknown contract fields are silently ignored via `ignoreUnknownKeys = true` on the parser.
 *
 * Supported [type] values: `error`, `rate_limit`, `thinking`, `tool_call`, `slow`, `timeout`,
 * `empty`. `normal` and unknown values are pass-through — the request falls back to normal
 * pool-based routing as if no directive was sent.
 */
@Serializable
data class FakerDirective(
    val type: String,
    val error: FakerDirectiveError? = null,
    val thinking: FakerDirectiveThinking? = null,
    val tool_call: FakerDirectiveToolCall? = null,
    val timing: FakerDirectiveTiming? = null,
)

/**
 * `error` sub-object from the directive. All fields optional — defaults are applied at
 * the routing-policy level (see `HeaderDirectivePolicy.toDecision`).
 *
 * Field name `http_status` mirrors the contract verbatim (snake_case from the JSON spec).
 */
@Serializable
data class FakerDirectiveError(
    val http_status: Int? = null,
    val code: String? = null,
    val message: String? = null,
)

/** `thinking` sub-object: min_tokens controls the length of the synthesized Thinking block. */
@Serializable
data class FakerDirectiveThinking(
    val min_tokens: Int? = null,
)

/**
 * `tool_call` sub-object: dictates the synthesized tool call name and argument keys.
 * Engine reads tool name from [RequestContext.toolNames] — the route handler overrides
 * `ctx.toolNames = [name]` before invoking the engine.
 */
@Serializable
data class FakerDirectiveToolCall(
    val name: String? = null,
    val args_keys: List<String>? = null,
)

/** `timing` sub-object: ttft / inter-token-latency overrides; converted to [RangeMs] in the engine. */
@Serializable
data class FakerDirectiveTiming(
    val ttft_ms: Long? = null,
    val itl_ms: Long? = null,
    val total_ms: Long? = null,
)
