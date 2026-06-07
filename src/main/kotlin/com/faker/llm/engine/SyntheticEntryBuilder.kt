package com.faker.llm.engine

import com.faker.llm.domain.FakerDirective
import com.faker.llm.domain.FinishReason
import com.faker.llm.domain.RangeInt
import com.faker.llm.domain.RangeMs
import com.faker.llm.domain.RequestContext
import com.faker.llm.domain.ResponsePart
import com.faker.llm.domain.SuccessEntry
import com.faker.llm.domain.TimingProfile
import kotlinx.serialization.json.JsonObject

/**
 * Synthesizes a [SuccessEntry] from a [FakerDirective] for directive types that don't pick
 * from the pool: `normal`, `empty`, `thinking`, `tool_call`. Adapter route handlers feed
 * the resulting entry into the normal streaming/non-streaming code path.
 *
 * `timeout` is NOT handled here — the route handler suspends with `delay(Long.MAX_VALUE)`
 * before any engine call.
 *
 * ### Length model (faker-contract.md §4)
 * For `normal` / `thinking` the stream length is derived from timing:
 * ```
 *   content_tokens = round((total_ms − ttft_ms) / itl_ms) + 1
 * ```
 * The formula collapses to a single token when `total_ms ≤ ttft_ms` or `itl_ms ≤ 0`,
 * matching the contract's "at least one content token" floor. `thinking` splits the
 * budget into a reasoning prefix (≥ `thinking.min_tokens`) and the remaining content.
 *
 * `tool_call` is exactly one tool-call event (no args). `empty` produces no content
 * parts at all (only `ttft_ms` matters).
 */
object SyntheticEntryBuilder {

    /**
     * Characters-per-token convention shared with the wire `Usage` mapping in adapters.
     * Also the synthetic chunk size: emitting exactly one token per chunk is what makes the
     * stream honor `itl_ms` per token, so wall-clock duration matches the contract's
     * `n = round((total−ttft)/itl)+1` length model (faker-contract.md §4). With a larger chunk
     * the engine would apply far fewer `itl` delays and the stream would collapse to a fraction
     * of `total_ms`.
     */
    private const val CHARS_PER_TOKEN = 4

    private const val DEFAULT_TTFT_MS = 300L
    private const val DEFAULT_ITL_MS = 20L
    private const val DEFAULT_TOTAL_MS = 2000L
    private const val DEFAULT_THINKING_MIN_TOKENS = 20
    private const val DEFAULT_TOOL_NAME = "fake_tool"

    /** Build a synthesized success entry for `normal` / `empty` / `thinking` / `tool_call`. */
    fun buildEntry(directive: FakerDirective): SuccessEntry = when (directive.type) {
        "empty" -> SuccessEntry(
            id = "synthetic-empty",
            weight = 1.0,
            parts = emptyList(),
            timing = timingFromDirective(directive),
        )

        "normal" -> {
            val tokens = contentTokensFromTiming(directive)
            SuccessEntry(
                id = "synthetic-normal",
                weight = 1.0,
                parts = listOf(ResponsePart.Text(buildWordContent(tokens * CHARS_PER_TOKEN))),
                timing = timingFromDirective(directive),
            )
        }

        "thinking" -> {
            val totalTokens = contentTokensFromTiming(directive)
            val minThinkingTokens = directive.thinking?.min_tokens?.takeIf { it > 0 }
                ?: DEFAULT_THINKING_MIN_TOKENS
            // Contract: total tokens are split between reasoning prefix and content.
            // Always emit at least one content token after the reasoning block.
            val thinkingTokens = minOf(minThinkingTokens, maxOf(totalTokens - 1, 1))
            val contentTokens = maxOf(totalTokens - thinkingTokens, 1)
            SuccessEntry(
                id = "synthetic-thinking",
                weight = 1.0,
                parts = listOf(
                    ResponsePart.Thinking(buildThinkingContent(thinkingTokens * CHARS_PER_TOKEN)),
                    ResponsePart.Text(buildWordContent(contentTokens * CHARS_PER_TOKEN)),
                ),
                timing = timingFromDirective(directive),
            )
        }

        "tool_call" -> SuccessEntry(
            id = "synthetic-tool-call",
            weight = 1.0,
            requiresTools = true,
            // Contract §5: tool_call is "one tool-call invocation" — no arguments to stream,
            // so the args template is an empty JSON object that yields zero arg chunks.
            parts = listOf(ResponsePart.ToolCall(JsonObject(emptyMap()))),
            timing = timingFromDirective(directive),
            // Contract §5/§6: a tool call must terminate with finish_reason "tool_calls".
            finishReason = FinishReason.ToolCalls,
        )

        else -> error("SyntheticEntryBuilder.buildEntry called with unsupported type: ${directive.type}")
    }

    /**
     * For `tool_call` we must override [RequestContext.toolNames] so the engine's random
     * tool-name pick yields the name from the directive. Other types pass-through.
     */
    fun overrideContext(ctx: RequestContext, directive: FakerDirective): RequestContext {
        if (directive.type != "tool_call") return ctx
        val name = directive.tool_call?.name?.takeIf { it.isNotBlank() } ?: DEFAULT_TOOL_NAME
        return ctx.copy(hasTools = true, toolNames = listOf(name))
    }

    /**
     * Implements faker-contract.md §4: `round((total_ms − ttft_ms) / itl_ms) + 1`,
     * clamped to a minimum of 1 (the contract floor — at least one content token).
     * `includeTotal=false` callers (`empty`, `tool_call`) should not invoke this.
     */
    private fun contentTokensFromTiming(directive: FakerDirective): Int {
        val ttft = directive.timing?.ttft_ms ?: DEFAULT_TTFT_MS
        val itl = (directive.timing?.itl_ms ?: DEFAULT_ITL_MS).takeIf { it > 0 } ?: DEFAULT_ITL_MS
        val total = directive.timing?.total_ms ?: DEFAULT_TOTAL_MS
        val deltaMs = total - ttft
        if (deltaMs <= 0) return 1
        // Math.round implements the contract's "round" — half-up at .5 boundaries.
        return Math.round(deltaMs.toDouble() / itl.toDouble()).toInt() + 1
    }

    /**
     * Builds a [TimingProfile] from the directive (defaults filled in for absent fields).
     * Chunk size is fixed at [CHARS_PER_TOKEN] so each emitted chunk is exactly one token and
     * the engine applies one `itl` delay per token — see the [CHARS_PER_TOKEN] note.
     */
    private fun timingFromDirective(directive: FakerDirective): TimingProfile {
        val ttft = directive.timing?.ttft_ms ?: DEFAULT_TTFT_MS
        val itl = directive.timing?.itl_ms ?: DEFAULT_ITL_MS
        return TimingProfile(
            ttftMs = RangeMs(ttft, ttft),
            interChunkMs = RangeMs(itl, itl),
            chunkSizeChars = RangeInt(CHARS_PER_TOKEN, CHARS_PER_TOKEN),
        )
    }

    /** Generate filler text of at least [targetLength] chars; we trim to exact size at the end. */
    private fun buildWordContent(targetLength: Int): String {
        if (targetLength <= 0) return ""
        val raw = buildString(targetLength + 16) {
            var step = 1
            while (length < targetLength) {
                append("word ").append(step).append(". ")
                step++
            }
        }
        return raw.take(targetLength)
    }

    private fun buildThinkingContent(targetLength: Int): String {
        if (targetLength <= 0) return ""
        val raw = buildString(targetLength + 32) {
            var step = 1
            while (length < targetLength) {
                append("Step ").append(step).append(": thinking about this carefully. ")
                step++
            }
        }
        return raw.take(targetLength)
    }
}
