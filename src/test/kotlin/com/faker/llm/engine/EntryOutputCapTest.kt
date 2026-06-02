package com.faker.llm.engine

import com.faker.llm.domain.FinishReason
import com.faker.llm.domain.RangeInt
import com.faker.llm.domain.RangeMs
import com.faker.llm.domain.ResponsePart
import com.faker.llm.domain.SuccessEntry
import com.faker.llm.domain.TimingProfile
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntryOutputCapTest {

    @Test
    fun `null outputTokens passes the entry through unchanged`() {
        val entry = entry(parts = listOf(ResponsePart.Text("hello world")))
        val result = EntryOutputCap.applyOutputCap(entry, null)
        assertSame(entry, result, "Expected identity pass-through for null cap")
    }

    @Test
    fun `non-positive outputTokens passes the entry through unchanged`() {
        val entry = entry(parts = listOf(ResponsePart.Text("hello world")))
        assertSame(entry, EntryOutputCap.applyOutputCap(entry, 0))
        assertSame(entry, EntryOutputCap.applyOutputCap(entry, -5))
    }

    @Test
    fun `outputTokens of 10 caps total text length to 40 chars`() {
        // 12 * 5 = 60 chars total > 40 budget, so the second part must be partially trimmed
        // and the third dropped entirely.
        val parts = listOf(
            ResponsePart.Text("a".repeat(25)),
            ResponsePart.Text("b".repeat(25)),
            ResponsePart.Text("c".repeat(10)),
        )
        val capped = EntryOutputCap.applyOutputCap(entry(parts = parts), 10)
        val texts = capped.parts.filterIsInstance<ResponsePart.Text>()
        val totalChars = texts.sumOf { it.content.length }
        assertEquals(40, totalChars, "Total text chars must equal 10 * CHARS_PER_TOKEN (40)")
        assertEquals(2, texts.size, "Third Text part should have been dropped")
        assertEquals(25, texts[0].content.length)
        assertEquals(15, texts[1].content.length)
        assertTrue(texts[1].content.all { it == 'b' })
    }

    @Test
    fun `thinking and tool_call parts pass through untouched even when text is capped`() {
        val thinkingContent = "deep thoughts ".repeat(20) // > 40 chars
        val tool = ResponsePart.ToolCall(buildJsonObject { put("city", JsonPrimitive("Moscow")) })
        val parts = listOf(
            ResponsePart.Thinking(thinkingContent),
            ResponsePart.Text("x".repeat(100)),
            tool,
        )
        val capped = EntryOutputCap.applyOutputCap(entry(parts = parts), 10)

        val resultParts = capped.parts
        assertEquals(3, resultParts.size, "Non-text parts must survive")
        assertTrue(resultParts[0] is ResponsePart.Thinking)
        assertEquals(thinkingContent, (resultParts[0] as ResponsePart.Thinking).content)
        assertTrue(resultParts[1] is ResponsePart.Text)
        assertEquals(40, (resultParts[1] as ResponsePart.Text).content.length)
        assertSame(tool, resultParts[2])
    }

    private fun entry(parts: List<ResponsePart>): SuccessEntry = SuccessEntry(
        id = "test",
        weight = 1.0,
        parts = parts,
        timing = TimingProfile(
            ttftMs = RangeMs(0, 0),
            interChunkMs = RangeMs(0, 0),
            chunkSizeChars = RangeInt(8, 16),
        ),
        finishReason = FinishReason.Stop,
    )
}
