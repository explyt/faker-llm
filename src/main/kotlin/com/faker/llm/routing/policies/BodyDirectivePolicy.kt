package com.faker.llm.routing.policies

import com.faker.llm.domain.FakerDirective
import com.faker.llm.domain.RequestContext
import com.faker.llm.routing.RoutingDecision
import com.faker.llm.routing.RoutingPolicy

/**
 * Maps the body-carried faker directive ([RequestContext.directive], parsed from
 * `x_faker.directive` per faker-contract.md) into a [RoutingDecision]:
 *  - `error` → [RoutingDecision.SyntheticHttpError] with the requested HTTP status
 *  - `normal` / `thinking` / `tool_call` / `timeout` / `empty` →
 *    [RoutingDecision.SyntheticBehavior] (the route handler then drives `SyntheticEntryBuilder`)
 *  - unknown / absent → `null` pass-through; the request falls through to the next policy
 *    and finally the weighted pool selector. Strict closed-list enforcement is the client's
 *    responsibility per the contract — the faker stays tolerant.
 *
 * Unlike the legacy [HeaderDirectivePolicy] there is no JSON parsing here: the directive was
 * already decoded as part of the request body by the adapter. Stateless / thread-safe.
 */
class BodyDirectivePolicy : RoutingPolicy {

    override fun decide(ctx: RequestContext): RoutingDecision? {
        val directive = ctx.directive ?: return null
        return toDecision(directive)
    }

    /** Maps a parsed directive to a decision, or `null` for pass-through. */
    private fun toDecision(directive: FakerDirective): RoutingDecision? = when (directive.type) {
        TYPE_ERROR -> RoutingDecision.SyntheticHttpError(
            status = directive.error?.http_status ?: DEFAULT_ERROR_STATUS,
        )
        TYPE_NORMAL, TYPE_TIMEOUT, TYPE_EMPTY, TYPE_THINKING, TYPE_TOOL_CALL ->
            RoutingDecision.SyntheticBehavior(directive)
        else -> null
    }

    companion object {
        private const val TYPE_ERROR = "error"
        private const val TYPE_NORMAL = "normal"
        private const val TYPE_TIMEOUT = "timeout"
        private const val TYPE_EMPTY = "empty"
        private const val TYPE_THINKING = "thinking"
        private const val TYPE_TOOL_CALL = "tool_call"

        private const val DEFAULT_ERROR_STATUS = 500
    }
}
