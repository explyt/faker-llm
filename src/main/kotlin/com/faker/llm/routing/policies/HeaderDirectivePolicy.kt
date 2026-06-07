package com.faker.llm.routing.policies

import com.faker.llm.domain.FakerDirective
import com.faker.llm.domain.RequestContext
import com.faker.llm.routing.RoutingDecision
import com.faker.llm.routing.RoutingPolicy
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Parses the `X-Faker-Directive` JSON header (see `faker-contract 2.md`) and routes each
 * directive type into a [RoutingDecision]:
 *  - `error` → [RoutingDecision.SyntheticHttpError] with the requested HTTP status
 *  - `normal` / `thinking` / `tool_call` / `timeout` / `empty` →
 *    [RoutingDecision.SyntheticBehavior] (the route handler then drives `SyntheticEntryBuilder`)
 *  - unknown → `null` pass-through; the request falls through to `PromptDirectivePolicy`
 *    and finally the weighted pool selector. Strict closed-list enforcement is the client's
 *    responsibility per v2 of the contract — the faker stays tolerant on the wire.
 *
 * Tolerant on malformed JSON: parsing failure is logged at WARN and the policy returns
 * `null` so the request still goes through the normal pipeline.
 *
 * Stateless / thread-safe — the [Json] instance has no mutable state.
 */
class HeaderDirectivePolicy : RoutingPolicy {

    private val log = LoggerFactory.getLogger(HeaderDirectivePolicy::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        // Default `coerceInputValues = false` — we want to KNOW if the contract evolves
        // in an incompatible way, not silently coerce nulls into typed fields.
    }

    override fun decide(ctx: RequestContext): RoutingDecision? {
        val raw = ctx.directiveHeader ?: return null

        val directive = runCatching { json.decodeFromString<FakerDirective>(raw) }
            .getOrElse { e ->
                log.warn("Ignoring malformed X-Faker-Directive: {} (cause: {})", raw, e.message)
                return null
            }

        return toDecision(directive)
    }

    /** Maps a parsed directive to a decision, or `null` for pass-through. */
    private fun toDecision(directive: FakerDirective): RoutingDecision? = when (directive.type) {
        TYPE_ERROR -> RoutingDecision.SyntheticHttpError(
            status = directive.error?.http_status ?: DEFAULT_ERROR_STATUS,
        )
        // Synthesized success responses — the route handler builds the entry via
        // SyntheticEntryBuilder, bypassing the pool entirely. `normal` joins the
        // synthetic family in v2: length is derived from timing, not from the pool.
        TYPE_NORMAL, TYPE_TIMEOUT, TYPE_EMPTY, TYPE_THINKING, TYPE_TOOL_CALL ->
            RoutingDecision.SyntheticBehavior(directive)
        else -> null
    }

    companion object {
        /** HTTP header carrying the directive JSON (see faker-contract 2.md). */
        const val HEADER_NAME = "X-Faker-Directive"

        private const val TYPE_ERROR = "error"
        private const val TYPE_NORMAL = "normal"
        private const val TYPE_TIMEOUT = "timeout"
        private const val TYPE_EMPTY = "empty"
        private const val TYPE_THINKING = "thinking"
        private const val TYPE_TOOL_CALL = "tool_call"

        // Kept in lockstep with PromptDirectivePolicy's default so `type=error` without a status
        // yields the same HTTP status on every surface (faker-contract.md §2). In practice the
        // client always sends an explicit status, so this fallback rarely fires.
        private const val DEFAULT_ERROR_STATUS = 400
    }
}
