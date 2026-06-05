package com.faker.llm.engine

import com.faker.llm.domain.AbstractStreamEvent
import com.faker.llm.domain.FakerDirective
import com.faker.llm.domain.FakerDirectiveThinking
import com.faker.llm.domain.FakerDirectiveTiming
import com.faker.llm.domain.FakerDirectiveToolCall
import com.faker.llm.domain.FinishReason
import com.faker.llm.domain.RangeInt
import com.faker.llm.domain.RequestContext
import com.faker.llm.domain.ResponsePart
import com.faker.llm.domain.SuccessEntry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntheticEntryBuilderTest {

    private fun ctx(stream: Boolean = true) = RequestContext(
        hasTools = false,
        toolNames = emptyList(),
        stream = stream,
        model = "x",
        inspectableContent = null,
    )

    @Test
    fun `normal entry derives token count from duration and chunks one token at a time`() {
        // n = round((2200-200)/20)+1 = 101; content chars = n * CHARS_PER_TOKEN (4) = 404.
        val directive = FakerDirective(
            type = "normal",
            timing = FakerDirectiveTiming(ttft_ms = 200, itl_ms = 20, total_ms = 2200),
        )
        val entry = SyntheticEntryBuilder.buildEntry(directive)
        // Chunk size fixed at one token so the engine applies one itl delay per token.
        assertEquals(RangeInt(4, 4), entry.timing.chunkSizeChars)
        val text = (entry.parts.single() as ResponsePart.Text).content
        assertEquals(404, text.length)
    }

    @Test
    fun `tool_call entry finishes with tool_calls and requires tools`() {
        val directive = FakerDirective(type = "tool_call", tool_call = FakerDirectiveToolCall(name = "get_weather"))
        val entry = SyntheticEntryBuilder.buildEntry(directive)
        assertEquals(FinishReason.ToolCalls, entry.finishReason)
        assertTrue(entry.requiresTools)
        // overrideContext must surface the directive's tool name to the engine.
        val effective = SyntheticEntryBuilder.overrideContext(ctx(), directive)
        assertEquals(listOf("get_weather"), effective.toolNames)
        assertTrue(effective.hasTools)
    }

    @Test
    fun `thinking entry emits a reasoning part before the content part`() {
        val directive = FakerDirective(
            type = "thinking",
            timing = FakerDirectiveTiming(ttft_ms = 200, itl_ms = 20, total_ms = 2200),
            thinking = FakerDirectiveThinking(min_tokens = 20),
        )
        val entry = SyntheticEntryBuilder.buildEntry(directive)
        assertEquals(2, entry.parts.size)
        assertTrue(entry.parts[0] is ResponsePart.Thinking, "first part must be the reasoning block")
        assertTrue(entry.parts[1] is ResponsePart.Text, "second part must be the content")
    }

    @Test
    fun `empty entry has no parts`() {
        val entry = SyntheticEntryBuilder.buildEntry(FakerDirective(type = "empty"))
        assertTrue(entry.parts.isEmpty())
    }

    @Test
    fun `synthetic normal stream honors itl per token so duration equals total`() = runTest {
        // The contract length model only holds if the faker REALLY pauses itl_ms between tokens
        // (trap a). With virtual time we can assert the exact wall-clock the stream would take.
        val directive = FakerDirective(
            type = "normal",
            timing = FakerDirectiveTiming(ttft_ms = 200, itl_ms = 20, total_ms = 2200),
        )
        val entry: SuccessEntry = SyntheticEntryBuilder.buildEntry(directive)
        val engine = DefaultStreamingEngine()

        val events = engine.execute(entry, ctx()).toList()

        val tokens = events.count { it is AbstractStreamEvent.TextChunk }
        assertEquals(101, tokens, "one content frame per derived token (n = 101)")
        // ttft + (n-1)*itl = 200 + 100*20 = 2200 ms of virtual time actually elapsed.
        assertEquals(
            2200L,
            testScheduler.currentTime,
            "stream duration must equal total_ms via real per-token itl pauses",
        )
    }

    @Test
    fun `synthetic thinking stream keeps itl across the reasoning-to-content boundary`() = runTest {
        // n = 101 total tokens; min_tokens=20 → 20 reasoning + 81 content. The engine must apply
        // one itl between EVERY pair of tokens, including the reasoning→content boundary, so the
        // duration still equals total_ms (no dropped itl at the part seam).
        val directive = FakerDirective(
            type = "thinking",
            timing = FakerDirectiveTiming(ttft_ms = 200, itl_ms = 20, total_ms = 2200),
            thinking = FakerDirectiveThinking(min_tokens = 20),
        )
        val entry: SuccessEntry = SyntheticEntryBuilder.buildEntry(directive)

        val events = DefaultStreamingEngine().execute(entry, ctx()).toList()

        assertEquals(20, events.count { it is AbstractStreamEvent.ThinkingChunk }, "reasoning tokens")
        assertEquals(81, events.count { it is AbstractStreamEvent.TextChunk }, "content tokens")
        // ttft + (n-1)*itl = 200 + 100*20 = 2200; a dropped boundary itl would yield 2180.
        assertEquals(
            2200L,
            testScheduler.currentTime,
            "thinking duration must equal total_ms with the boundary itl applied",
        )
    }
}
