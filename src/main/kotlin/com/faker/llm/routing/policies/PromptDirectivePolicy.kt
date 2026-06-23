package com.faker.llm.routing.policies

import com.faker.llm.domain.FakerDirective
import com.faker.llm.domain.FakerDirectiveError
import com.faker.llm.domain.FakerDirectiveReplay
import com.faker.llm.domain.FakerDirectiveTiming
import com.faker.llm.domain.RequestContext
import com.faker.llm.routing.RoutingDecision
import com.faker.llm.routing.RoutingPolicy

/**
 * Parses the single in-band faker directive marker carried inside the user-message text and maps
 * it to a [RoutingDecision]. This is the ONLY directive transport for the OpenAI surface: the
 * license tract (ЛК) strips the request body and custom headers in both directions, so the
 * directive rides inside `messages[].content` as a self-contained marker:
 *
 * ```
 * [[faker:type=<type>;status=<int>;ttft=<ms>;itl=<ms>;total=<ms>]]
 * ```
 *
 * Grammar mirrors the client reference `DirectiveMarker`/`ParseMarker` 1-for-1 so the round-trip
 * is exact (LoadTesting `internal/faker/marker.go`):
 *  - the LAST `[[faker:` … `]]` span wins; earlier markers are ignored (so a freshly typed
 *    directive in the latest user turn overrides a stale one left earlier in the conversation);
 *  - pairs are separated by `;`, key and value by the first `=`;
 *  - keys are case-insensitive and trimmed; unknown keys are ignored;
 *  - a non-numeric value parses to `0`;
 *  - if `type` is absent/empty the span is NOT a directive — returns `null` (ordinary pool answer).
 *
 * Mapping (the contract's behaviour table — synthesizes independently of the pool, since the
 * type/status/timing are fully specified by the marker):
 *  - `error` → [RoutingDecision.SyntheticHttpError] with `status` (default [DEFAULT_ERROR_STATUS]);
 *  - `normal` / `thinking` / `tool_call` / `timeout` / `empty` → [RoutingDecision.SyntheticBehavior];
 *  - any other (unknown) type → `null` pass-through.
 *
 * Stateless / thread-safe.
 */
class PromptDirectivePolicy : RoutingPolicy {

    override fun decide(ctx: RequestContext): RoutingDecision? {
        val directive = parseMarker(ctx.inspectableContent ?: return null) ?: return null
        return toDecision(directive)
    }

    /** Maps a parsed directive to a decision, or `null` for an unknown type (pool pass-through). */
    private fun toDecision(directive: FakerDirective): RoutingDecision? = when (directive.type) {
        TYPE_ERROR -> RoutingDecision.SyntheticHttpError(
            status = directive.error?.http_status ?: DEFAULT_ERROR_STATUS,
        )
        TYPE_NORMAL, TYPE_THINKING, TYPE_TOOL_CALL, TYPE_TIMEOUT, TYPE_EMPTY, TYPE_REPLAY ->
            RoutingDecision.SyntheticBehavior(directive)
        else -> null
    }

    /**
     * Extracts the LAST in-band marker into a [FakerDirective], or `null` when absent / typeless.
     * Inverse of the client's `DirectiveMarker`; see the class doc for the grammar.
     */
    private fun parseMarker(content: String): FakerDirective? {
        val start = content.lastIndexOf(DIRECTIVE_PREFIX)
        if (start < 0) return null
        val bodyStart = start + DIRECTIVE_PREFIX.length
        val end = content.indexOf(DIRECTIVE_SUFFIX, bodyStart)
        if (end < 0) return null // unterminated marker — nothing valid can follow

        var type = ""
        var status = 0
        var ttft = 0L
        var itl = 0L
        var total = 0L
        var payload = ""
        for (pair in content.substring(bodyStart, end).split(PAIR_SEPARATOR)) {
            val sep = pair.indexOf(KEY_VALUE_SEPARATOR)
            if (sep < 0) continue
            val key = pair.substring(0, sep).trim().lowercase()
            val value = pair.substring(sep + 1).trim()
            when (key) {
                KEY_TYPE -> type = value
                // Non-numeric → 0 (Atoi-equivalent), mirroring the client's tolerant parse.
                KEY_STATUS -> status = value.toIntOrNull() ?: 0
                KEY_TTFT -> ttft = value.toLongOrNull() ?: 0L
                KEY_ITL -> itl = value.toLongOrNull() ?: 0L
                KEY_TOTAL -> total = value.toLongOrNull() ?: 0L
                // base64url(no-pad) recorded message for type=replay; kept verbatim.
                KEY_PAYLOAD -> payload = value
            }
        }
        if (type.isEmpty()) return null
        return FakerDirective(
            type = type,
            error = if (status != 0) FakerDirectiveError(http_status = status) else null,
            timing = if (ttft != 0L || itl != 0L || total != 0L) {
                FakerDirectiveTiming(ttft_ms = ttft, itl_ms = itl, total_ms = total)
            } else {
                null
            },
            replay = if (payload.isNotEmpty()) FakerDirectiveReplay(payload = payload) else null,
        )
    }

    companion object {
        private const val DIRECTIVE_PREFIX = "[[faker:"
        private const val DIRECTIVE_SUFFIX = "]]"
        private const val PAIR_SEPARATOR = ';'
        private const val KEY_VALUE_SEPARATOR = '='

        private const val KEY_TYPE = "type"
        private const val KEY_STATUS = "status"
        private const val KEY_TTFT = "ttft"
        private const val KEY_ITL = "itl"
        private const val KEY_TOTAL = "total"
        private const val KEY_PAYLOAD = "payload"

        private const val TYPE_ERROR = "error"
        private const val TYPE_NORMAL = "normal"
        private const val TYPE_THINKING = "thinking"
        private const val TYPE_TOOL_CALL = "tool_call"
        private const val TYPE_TIMEOUT = "timeout"
        private const val TYPE_EMPTY = "empty"
        private const val TYPE_REPLAY = "replay"

        /** Status used for `type=error` when the marker omits `status` (faker-contract.md §2). */
        private const val DEFAULT_ERROR_STATUS = 400
    }
}
