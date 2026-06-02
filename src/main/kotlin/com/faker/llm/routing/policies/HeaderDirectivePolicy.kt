package com.faker.llm.routing.policies

import com.faker.llm.domain.FakerDirective
import com.faker.llm.domain.RequestContext
import com.faker.llm.routing.RoutingDecision
import com.faker.llm.routing.RoutingPolicy
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Parses the `X-Faker-Directive` JSON header (see `faker-contract.md`) and routes each
 * directive type into a [RoutingDecision]:
 *  - `error` / `rate_limit` → [RoutingDecision.SyntheticHttpError]
 *  - `slow` / `timeout` / `empty` / `thinking` / `tool_call` → [RoutingDecision.SyntheticBehavior]
 *    (the route handler then drives `SyntheticEntryBuilder` and honors `tokens.output`)
 *  - `normal` / unknown → `null` pass-through; the request continues through
 *    `PromptDirectivePolicy` and finally weighted pool selection. `tokens.output` is still
 *    honored in the pass-through case — the route handler parses it independently and
 *    applies it via `EntryOutputCap` to the pool-picked entry.
 *
 * Tolerant on malformed JSON: parsing failure is logged at WARN and the policy returns
 * `null` so the request still goes through the normal pipeline. This keeps load-test
 * clients from breaking when a middlebox truncates the header or when the contract
 * implementation on the other side is still in flight.
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
        TYPE_ERROR -> {
            val e = directive.error
            RoutingDecision.SyntheticHttpError(
                status = e?.http_status ?: DEFAULT_ERROR_STATUS,
                code = e?.code,
                message = e?.message ?: DEFAULT_ERROR_MESSAGE,
            )
        }
        TYPE_RATE_LIMIT -> {
            val e = directive.error
            RoutingDecision.SyntheticHttpError(
                status = RATE_LIMIT_STATUS,
                code = e?.code ?: DEFAULT_RATE_LIMIT_CODE,
                message = e?.message ?: DEFAULT_RATE_LIMIT_MESSAGE,
            )
        }
        // Synthesized success responses — the route handler builds the entry via
        // SyntheticEntryBuilder, bypassing the pool entirely.
        TYPE_SLOW, TYPE_TIMEOUT, TYPE_EMPTY, TYPE_THINKING, TYPE_TOOL_CALL ->
            RoutingDecision.SyntheticBehavior(directive)
        // `normal` and unknown types fall through to the pool (tolerant).
        else -> null
    }

    companion object {
        /** HTTP header carrying the directive JSON (see faker-contract.md). */
        const val HEADER_NAME = "X-Faker-Directive"

        private const val TYPE_ERROR = "error"
        private const val TYPE_RATE_LIMIT = "rate_limit"
        private const val TYPE_SLOW = "slow"
        private const val TYPE_TIMEOUT = "timeout"
        private const val TYPE_EMPTY = "empty"
        private const val TYPE_THINKING = "thinking"
        private const val TYPE_TOOL_CALL = "tool_call"

        private const val RATE_LIMIT_STATUS = 429
        private const val DEFAULT_ERROR_STATUS = 500
        private const val DEFAULT_ERROR_MESSAGE = "Faker injected error"
        private const val DEFAULT_RATE_LIMIT_CODE = "rate_limit_exceeded"
        private const val DEFAULT_RATE_LIMIT_MESSAGE = "Rate limit exceeded"
    }
}
