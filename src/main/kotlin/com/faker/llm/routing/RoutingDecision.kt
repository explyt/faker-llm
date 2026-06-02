package com.faker.llm.routing

import com.faker.llm.domain.FakerDirective

/**
 * Instruction for how [com.faker.llm.pool.PoolSelector] should narrow the pool before the
 * weighted pick. Produced by the `RequestRouter` (Task 04) from `[[faker:...]]` directives;
 * defaults to [Default] (no narrowing).
 */
sealed interface RoutingDecision {

    /** No forced narrowing — normal weighted pick over all applicable entries. */
    data object Default : RoutingDecision

    /** Keep only the entry whose `id` equals [id]. */
    data class ForceEntryId(val id: String) : RoutingDecision

    /** Keep only entries whose `tag` equals [tag]. */
    data class RequireTag(val tag: String) : RoutingDecision

    /** Keep only `HttpErrorEntry` entries whose `status` equals [status]. */
    data class ForceHttpStatus(val status: Int) : RoutingDecision

    /**
     * Short-circuits the pipeline: route handlers must respond with an HTTP error built
     * from these fields WITHOUT calling [com.faker.llm.pool.PoolSelector]. Produced by
     * `HeaderDirectivePolicy` from the `X-Faker-Directive` header.
     *
     * @param status raw HTTP status code (e.g. 429, 500, 503)
     * @param code optional provider-agnostic error code (e.g. `rate_limit_exceeded`).
     *   For OpenAI flows it maps to `error.code`; for Anthropic flows it becomes
     *   the `error.type` (Anthropic has no separate code field).
     * @param message human-readable error message echoed verbatim in the response body
     */
    data class SyntheticHttpError(
        val status: Int,
        val code: String?,
        val message: String,
    ) : RoutingDecision

    /**
     * Short-circuits the pool: route handlers must SYNTHESIZE the response from [directive]
     * WITHOUT calling [com.faker.llm.pool.PoolSelector]. Produced by `HeaderDirectivePolicy`
     * for `thinking` / `tool_call` / `slow` / `timeout` / `empty` directive types.
     *
     * The actual response shape is built by `SyntheticEntryBuilder` in the engine package.
     * Selector must NOT see this decision — reaching the selector with it is a wiring bug
     * and the selector fails loudly.
     */
    data class SyntheticBehavior(val directive: FakerDirective) : RoutingDecision
}
