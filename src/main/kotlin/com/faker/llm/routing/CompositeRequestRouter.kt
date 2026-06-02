package com.faker.llm.routing

import com.faker.llm.domain.RequestContext

/**
 * Runs [policies] in order; the first non-null decision wins. If every policy passes
 * through (returns `null`), falls back to [RoutingDecision.Default].
 *
 * Stateless — natively safe under high concurrency.
 */
class CompositeRequestRouter(private val policies: List<RoutingPolicy>) : RequestRouter {
    override fun route(ctx: RequestContext): RoutingDecision {
        for (policy in policies) {
            policy.decide(ctx)?.let { return it }
        }
        return RoutingDecision.Default
    }
}
