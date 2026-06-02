package com.faker.llm.engine

import com.faker.llm.domain.ResponsePart
import com.faker.llm.domain.SuccessEntry

/** Characters-per-token convention used throughout the faker (see `Mapper.toUsage`). */
internal const val CHARS_PER_TOKEN = 4

/**
 * Applies the contract's `tokens.output` cap to a [SuccessEntry] BEFORE the engine plays it
 * back. We cap by trimming only [ResponsePart.Text] payloads, so that reasoning/thinking
 * and tool-call argument blocks remain intact — those are governed by other directive
 * sub-objects (`thinking.min_tokens`, `tool_call.args_keys`).
 *
 * Done as a pre-pass instead of inside `DefaultStreamingEngine` so the engine stays
 * stateless and the cap survives both streaming and non-streaming code paths.
 */
object EntryOutputCap {

    /**
     * Returns a copy of [entry] with text parts trimmed so the total length of `Text`
     * payloads does not exceed `outputTokens * CHARS_PER_TOKEN`. Non-text parts (Thinking,
     * ToolCall) are passed through untouched. When [outputTokens] is null or non-positive
     * the entry is returned unchanged — same as if no `tokens.output` directive was sent.
     */
    fun applyOutputCap(entry: SuccessEntry, outputTokens: Int?): SuccessEntry {
        if (outputTokens == null || outputTokens <= 0) return entry
        val budget = outputTokens * CHARS_PER_TOKEN
        var used = 0
        val newParts = mutableListOf<ResponsePart>()
        for (part in entry.parts) {
            when (part) {
                is ResponsePart.Text -> {
                    val remaining = budget - used
                    if (remaining <= 0) continue
                    val content = part.content
                    if (content.length <= remaining) {
                        newParts += part
                        used += content.length
                    } else {
                        newParts += ResponsePart.Text(content.take(remaining))
                        used = budget
                    }
                }
                is ResponsePart.Thinking, is ResponsePart.ToolCall -> newParts += part
            }
        }
        return entry.copy(parts = newParts)
    }
}
