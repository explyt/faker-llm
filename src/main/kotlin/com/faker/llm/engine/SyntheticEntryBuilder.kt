package com.faker.llm.engine

import com.faker.llm.domain.FakerDirective
import com.faker.llm.domain.RangeInt
import com.faker.llm.domain.RangeMs
import com.faker.llm.domain.RequestContext
import com.faker.llm.domain.ResponsePart
import com.faker.llm.domain.SuccessEntry
import com.faker.llm.domain.TimingProfile
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Synthesizes a [SuccessEntry] from a [FakerDirective] for directive types that don't pick
 * from the pool: `slow`, `empty`, `thinking`, `tool_call`. Adapter route handlers feed the
 * resulting entry into the normal streaming/non-streaming code path.
 *
 * `timeout` is NOT handled here — the route handler suspends with `delay(Long.MAX_VALUE)`
 * before any engine call.
 */
object SyntheticEntryBuilder {

    private const val DEFAULT_TTFT_MS = 300L
    private const val DEFAULT_ITL_MS = 20L
    private const val DEFAULT_CHUNK_MIN = 16
    private const val DEFAULT_CHUNK_MAX = 32
    private const val DEFAULT_THINKING_MIN_TOKENS = 20
    private const val DEFAULT_OUTPUT_TOKENS = 50
    private const val DEFAULT_TOOL_NAME = "fake_tool"
    private val DEFAULT_TOOL_ARG_KEYS = listOf("arg")

    private val defaultTiming = TimingProfile(
        ttftMs = RangeMs(DEFAULT_TTFT_MS, DEFAULT_TTFT_MS),
        interChunkMs = RangeMs(DEFAULT_ITL_MS, DEFAULT_ITL_MS),
        chunkSizeChars = RangeInt(DEFAULT_CHUNK_MIN, DEFAULT_CHUNK_MAX),
    )

    /** Build a synthesized success entry for `slow` / `empty` / `thinking` / `tool_call` types. */
    fun buildEntry(directive: FakerDirective): SuccessEntry = when (directive.type) {
        "empty" -> SuccessEntry(
            id = "synthetic-empty",
            weight = 1.0,
            parts = emptyList(),
            timing = defaultTiming,
        )

        "slow" -> {
            val outputTokens = directive.tokens?.output?.takeIf { it > 0 } ?: DEFAULT_OUTPUT_TOKENS
            SuccessEntry(
                id = "synthetic-slow",
                weight = 1.0,
                parts = listOf(ResponsePart.Text(buildWordContent(outputTokens * CHARS_PER_TOKEN))),
                timing = timingFromDirective(directive),
            )
        }

        "thinking" -> {
            val minTokens = directive.thinking?.min_tokens ?: DEFAULT_THINKING_MIN_TOKENS
            val thinkingContent = buildThinkingContent(minTokens * CHARS_PER_TOKEN)
            val outputTokens = directive.tokens?.output?.takeIf { it > 0 } ?: DEFAULT_OUTPUT_TOKENS
            val textContent = buildWordContent(outputTokens * CHARS_PER_TOKEN)
            SuccessEntry(
                id = "synthetic-thinking",
                weight = 1.0,
                parts = listOf(ResponsePart.Thinking(thinkingContent), ResponsePart.Text(textContent)),
                timing = defaultTiming,
            )
        }

        "tool_call" -> {
            val argsKeys = directive.tool_call?.args_keys?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_TOOL_ARG_KEYS
            val argsTemplate = buildJsonObject {
                for (key in argsKeys) put(key, JsonPrimitive("placeholder_$key"))
            }
            SuccessEntry(
                id = "synthetic-tool-call",
                weight = 1.0,
                requiresTools = true,
                parts = listOf(ResponsePart.ToolCall(argsTemplate)),
                timing = defaultTiming,
            )
        }

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

    private fun timingFromDirective(directive: FakerDirective): TimingProfile {
        val ttft = directive.timing?.ttft_ms ?: DEFAULT_TTFT_MS
        val itl = directive.timing?.itl_ms ?: DEFAULT_ITL_MS
        return TimingProfile(
            ttftMs = RangeMs(ttft, ttft),
            interChunkMs = RangeMs(itl, itl),
            chunkSizeChars = RangeInt(DEFAULT_CHUNK_MIN, DEFAULT_CHUNK_MAX),
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
        return raw
    }
}
