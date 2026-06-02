package com.faker.llm.routing

import com.faker.llm.domain.RequestContext

/**
 * A single routing rule. Returns a [RoutingDecision] to claim the request, or `null`
 * to pass through to the next policy. Kept as an interface even with one implementation
 * so future policies (model-based, randomized, ...) are drop-in.
 */
fun interface RoutingPolicy {
    fun decide(ctx: RequestContext): RoutingDecision?
}
