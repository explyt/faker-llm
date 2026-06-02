package com.faker.llm.routing.policies

import com.faker.llm.domain.RequestContext
import com.faker.llm.routing.RoutingDecision
import com.faker.llm.routing.RoutingPolicy

/**
 * Scans [RequestContext.inspectableContent] for inline directives of the form
 * `[[faker:<key>:<value>]]` and turns the first valid one (by position) into a [RoutingDecision].
 *
 * Recognized keys:
 * - `force_id`     → [RoutingDecision.ForceEntryId]
 * - `force_tag`    → [RoutingDecision.RequireTag]
 * - `force_status` → [RoutingDecision.ForceHttpStatus]
 *
 * Marker lookup is case-insensitive, but the extracted value keeps its original case
 * (entry ids and tags may be case-sensitive). An unrecognized or malformed directive is
 * skipped, and scanning continues. Returns `null` when no valid directive is present.
 *
 * Substring scan is intentionally cheap (KISS); revisit only if Task 11 shows it as a bottleneck.
 */
class PromptDirectivePolicy : RoutingPolicy {

    override fun decide(ctx: RequestContext): RoutingDecision? {
        val content = ctx.inspectableContent ?: return null

        var searchFrom = 0
        while (searchFrom <= content.length) {
            // ignoreCase keeps indices anchored to the ORIGINAL string (no lowercase()
            // length-drift on exotic Unicode), so the extracted value preserves its case.
            val start = content.indexOf(DIRECTIVE_PREFIX, searchFrom, ignoreCase = true)
            if (start < 0) return null

            val bodyStart = start + DIRECTIVE_PREFIX.length
            val end = content.indexOf(DIRECTIVE_SUFFIX, bodyStart, ignoreCase = true)
            if (end < 0) return null // unterminated marker — nothing valid can follow

            parseBody(content.substring(bodyStart, end))?.let { return it }
            searchFrom = end + DIRECTIVE_SUFFIX.length
        }
        return null
    }

    /** Parses a `<key>:<value>` body into a decision, or `null` if unrecognized/malformed. */
    private fun parseBody(body: String): RoutingDecision? {
        val sep = body.indexOf(KEY_VALUE_SEPARATOR)
        if (sep < 0) return null
        val key = body.substring(0, sep).trim().lowercase()
        val value = body.substring(sep + 1).trim()
        if (value.isEmpty()) return null
        return when (key) {
            KEY_FORCE_ID -> RoutingDecision.ForceEntryId(value)
            KEY_FORCE_TAG -> RoutingDecision.RequireTag(value)
            KEY_FORCE_STATUS -> value.toIntOrNull()?.let { RoutingDecision.ForceHttpStatus(it) }
            else -> null
        }
    }

    companion object {
        private const val DIRECTIVE_PREFIX = "[[faker:"
        private const val DIRECTIVE_SUFFIX = "]]"
        private const val KEY_VALUE_SEPARATOR = ':'
        private const val KEY_FORCE_ID = "force_id"
        private const val KEY_FORCE_TAG = "force_tag"
        private const val KEY_FORCE_STATUS = "force_status"
    }
}
