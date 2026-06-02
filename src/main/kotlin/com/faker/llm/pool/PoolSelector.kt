package com.faker.llm.pool

import com.faker.llm.domain.HttpErrorEntry
import com.faker.llm.domain.PoolEntry
import com.faker.llm.domain.RequestContext
import com.faker.llm.routing.RoutingDecision
import kotlin.random.Random

/**
 * Picks a [PoolEntry] for a request: narrow by [RoutingDecision], then by applicability to the
 * [RequestContext], then weighted-random pick. Holds an already-parsed, immutable entry list
 * (loaded once at startup by [PoolLoader]).
 *
 * @param entries all loaded entries (success + http_error live in the same pool)
 * @param random injectable for deterministic tests; production uses [Random.Default]
 */
class PoolSelector(
    private val entries: List<PoolEntry>,
    private val random: Random = Random.Default,
) {

    /**
     * @throws EmptyPoolException when no entry survives filtering — never falls back silently.
     */
    fun pick(ctx: RequestContext, decision: RoutingDecision = RoutingDecision.Default): PoolEntry {
        val afterDecision = entries.filter { it.matches(decision) }
        val applicable = afterDecision.filter { it.applicableTo(ctx) }
        if (applicable.isEmpty()) {
            throw EmptyPoolException(
                "No applicable pool entry: decision=$decision, hasTools=${ctx.hasTools} " +
                    "(${entries.size} total → ${afterDecision.size} after decision → 0 applicable)"
            )
        }
        return weightedPick(applicable)
    }

    private fun PoolEntry.matches(decision: RoutingDecision): Boolean = when (decision) {
        is RoutingDecision.Default -> true
        is RoutingDecision.ForceEntryId -> id == decision.id
        is RoutingDecision.RequireTag -> tag == decision.tag
        is RoutingDecision.ForceHttpStatus -> this is HttpErrorEntry && status == decision.status
        // SyntheticHttpError must be handled by the adapter BEFORE the selector is called —
        // it doesn't correspond to any pool entry. Reaching here means a route handler
        // forgot to short-circuit; fail loudly so the bug surfaces in tests, not under load.
        is RoutingDecision.SyntheticHttpError -> error(
            "SyntheticHttpError must be handled by the route handler, not the selector: $decision"
        )
        // Same rule for SyntheticBehavior — the entry is synthesized, never picked from the pool.
        is RoutingDecision.SyntheticBehavior -> error(
            "SyntheticBehavior must be handled by the route handler, not the selector: $decision"
        )
    }

    /**
     * A tool-requiring entry is inapplicable to a tool-less request. Everything else
     * (including all HTTP error entries, which carry `requiresTools = false`) always applies.
     */
    private fun PoolEntry.applicableTo(ctx: RequestContext): Boolean = !requiresTools || ctx.hasTools

    /** Linear walk over prefix weights — pool size is small, no need for binary search. */
    private fun weightedPick(candidates: List<PoolEntry>): PoolEntry {
        val totalWeight = candidates.sumOf { it.weight }
        var threshold = random.nextDouble(totalWeight)
        for (entry in candidates) {
            threshold -= entry.weight
            if (threshold < 0.0) return entry
        }
        // Floating-point safety net: rounding could leave threshold ~0 without crossing.
        return candidates.last()
    }
}
