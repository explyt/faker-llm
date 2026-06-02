package com.faker.llm.routing

import com.faker.llm.domain.RequestContext

/**
 * Decides how to narrow the pool for a request, based solely on the normalized
 * [RequestContext] (in practice its `inspectableContent`). No HTTP/header coupling.
 */
fun interface RequestRouter {
    fun route(ctx: RequestContext): RoutingDecision
}
