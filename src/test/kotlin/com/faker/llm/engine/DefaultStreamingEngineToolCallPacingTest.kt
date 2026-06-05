package com.faker.llm.engine

import com.faker.llm.domain.AbstractStreamEvent
import com.faker.llm.domain.FinishReason
import com.faker.llm.domain.RangeInt
import com.faker.llm.domain.RangeMs
import com.faker.llm.domain.RequestContext
import com.faker.llm.domain.ResponsePart
import com.faker.llm.domain.SuccessEntry
import com.faker.llm.domain.TimingProfile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the inter-token pacing around `ToolCallStart`. The client counts `ToolCallStart` as a
 * token (it carries the function name) and measures its ITL gap, so the engine must place one
 * `itl` before it whenever a prior token exists — and exactly one before the first args chunk.
 */
class DefaultStreamingEngineToolCallPacingTest {

    // One token per 4-char chunk; ttft=100, itl=10.
    private val timing = TimingProfile(
        ttftMs = RangeMs(100, 100),
        interChunkMs = RangeMs(10, 10),
        chunkSizeChars = RangeInt(4, 4),
    )

    private val ctx = RequestContext(
        hasTools = true,
        toolNames = listOf("get_weather"),
        stream = true,
        model = "x",
        inspectableContent = null,
    )

    @Test
    fun `text before tool call keeps an itl before ToolCallStart`() = runTest {
        // parts: Text("aaaabbbb") → 2 text chunks, then a ToolCall with empty args → "{}" = 1 chunk.
        // Client tokens: text#1, text#2, ToolCallStart, args#1 = 4 tokens → 3 itl gaps.
        val entry = SuccessEntry(
            id = "text-then-tool",
            weight = 1.0,
            requiresTools = true,
            parts = listOf(
                ResponsePart.Text("aaaabbbb"),
                ResponsePart.ToolCall(JsonObject(emptyMap())),
            ),
            timing = timing,
            finishReason = FinishReason.ToolCalls,
        )

        val events = DefaultStreamingEngine().execute(entry, ctx).toList()

        assertEquals(2, events.count { it is AbstractStreamEvent.TextChunk })
        assertEquals(1, events.count { it is AbstractStreamEvent.ToolCallStart })
        assertEquals(1, events.count { it is AbstractStreamEvent.ToolCallArgsChunk })
        // ttft + 3*itl = 100 + 30 = 130. The dropped-seam bug would yield 120 (missing the
        // text#2 → ToolCallStart pause).
        assertEquals(
            130L,
            testScheduler.currentTime,
            "an itl must separate the last text token from ToolCallStart",
        )
    }

    @Test
    fun `pure tool call paces only the args chunk not ToolCallStart`() = runTest {
        // parts: one ToolCall, empty args → "{}" = 1 chunk. Tokens: ToolCallStart, args#1 = 2 → 1 gap.
        val entry = SuccessEntry(
            id = "tool-only",
            weight = 1.0,
            requiresTools = true,
            parts = listOf(ResponsePart.ToolCall(JsonObject(emptyMap()))),
            timing = timing,
            finishReason = FinishReason.ToolCalls,
        )

        val events = DefaultStreamingEngine().execute(entry, ctx).toList()

        assertEquals(1, events.count { it is AbstractStreamEvent.ToolCallStart })
        assertEquals(1, events.count { it is AbstractStreamEvent.ToolCallArgsChunk })
        // ttft + 1*itl = 110: ToolCallStart rides on the TTFT lead (no extra itl), one itl before
        // the single args chunk. No double pause.
        assertEquals(
            110L,
            testScheduler.currentTime,
            "ToolCallStart as the first token must not get an extra itl; args#0 gets exactly one",
        )
    }
}
