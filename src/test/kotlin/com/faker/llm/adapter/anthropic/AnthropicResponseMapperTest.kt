package com.faker.llm.adapter.anthropic

import com.faker.llm.adapter.anthropic.dto.MessagesResponse
import com.faker.llm.domain.AbstractStreamEvent
import com.faker.llm.domain.FinishReason
import com.faker.llm.domain.RequestContext
import com.faker.llm.domain.UsageStub
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden tests for the Anthropic SSE render after the in-band cleanup: the wire must stay a clean,
 * spec-shaped Anthropic stream with NO faker echo (`faker_elapsed_ms`, `request_id`). Mirrors
 * OpenAiResponseMapperTest. (Pre-cleanup the Anthropic surface had no tests at all.)
 */
class AnthropicResponseMapperTest {

    private val mapper = AnthropicResponseMapper()
    private val ctx = RequestContext(
        hasTools = false,
        toolNames = emptyList(),
        stream = true,
        model = "faker",
        inspectableContent = "hi",
    )

    private fun textStream() = flowOf<AbstractStreamEvent>(
        AbstractStreamEvent.StreamStart,
        AbstractStreamEvent.TextChunk("hello"),
        AbstractStreamEvent.StreamEnd(
            finishReason = FinishReason.Stop,
            usage = UsageStub(promptChars = 40, completionChars = 20),
        ),
    )

    @Test
    fun `streaming emits the Anthropic event sequence in order`() = runTest {
        val writer = StringWriter()
        mapper.streamSse(textStream(), ctx, "faker", writer)
        val output = writer.toString()

        // The event: line names must appear in spec order.
        val order = listOf(
            "event: message_start",
            "event: content_block_start",
            "event: content_block_delta",
            "event: content_block_stop",
            "event: message_delta",
            "event: message_stop",
        )
        var idx = -1
        for (name in order) {
            val at = output.indexOf(name)
            assertTrue(at > idx, "event $name out of order or missing in:\n$output")
            idx = at
        }
        assertTrue(output.contains("\"type\":\"text_delta\""), output)
        assertTrue(output.contains("\"text\":\"hello\""), output)
        assertTrue(output.contains("\"stop_reason\":\"end_turn\""), output)
    }

    @Test
    fun `streaming carries no faker echo on any frame`() = runTest {
        val writer = StringWriter()
        mapper.streamSse(textStream(), ctx, "faker", writer)
        val output = writer.toString()
        assertFalse(output.contains("faker_elapsed_ms"), "stream must not carry faker_elapsed_ms:\n$output")
        assertFalse(output.contains("request_id"), "stream must not carry request_id:\n$output")
        assertFalse(output.contains("x_faker"), "stream must not carry x_faker:\n$output")
    }

    @Test
    fun `thinking rides in thinking_delta and is separate from text`() = runTest {
        val writer = StringWriter()
        val events = flowOf<AbstractStreamEvent>(
            AbstractStreamEvent.StreamStart,
            AbstractStreamEvent.ThinkingChunk("ponder"),
            AbstractStreamEvent.TextChunk("answer"),
            AbstractStreamEvent.StreamEnd(
                finishReason = FinishReason.Stop,
                usage = UsageStub(promptChars = 40, completionChars = 20),
            ),
        )
        mapper.streamSse(events, ctx, "faker", writer)
        val output = writer.toString()
        assertTrue(output.contains("\"type\":\"thinking\""), "thinking block start missing:\n$output")
        assertTrue(output.contains("\"type\":\"thinking_delta\""), output)
        assertTrue(output.contains("\"thinking\":\"ponder\""), output)
        assertTrue(output.contains("\"text\":\"answer\""), output)
    }

    @Test
    fun `tool_use streams content_block_start then input_json_delta with tool_use stop_reason`() = runTest {
        val writer = StringWriter()
        val events = flowOf<AbstractStreamEvent>(
            AbstractStreamEvent.StreamStart,
            AbstractStreamEvent.ToolCallStart("read_file", "c1"),
            AbstractStreamEvent.ToolCallArgsChunk("{\"path\":\"a.go\"}"),
            AbstractStreamEvent.ToolCallEnd("c1"),
            AbstractStreamEvent.StreamEnd(
                finishReason = FinishReason.ToolCalls,
                usage = UsageStub(promptChars = 40, completionChars = 8),
            ),
        )
        mapper.streamSse(events, ctx, "faker", writer)
        val output = writer.toString()
        assertTrue(output.contains("\"type\":\"tool_use\""), "tool_use block start missing:\n$output")
        assertTrue(output.contains("\"name\":\"read_file\""), output)
        assertTrue(output.contains("\"type\":\"input_json_delta\""), output)
        assertTrue(output.contains("\"partial_json\""), output)
        assertTrue(output.contains("\"stop_reason\":\"tool_use\""), output)
        assertFalse(output.contains("faker_elapsed_ms"), output)
    }

    @Test
    fun `non-streaming response is clean Anthropic with no echo`() = runTest {
        val response = mapper.buildNonStreaming(textStream(), ctx, "faker")
        val encoded = AnthropicJson.json.encodeToString(MessagesResponse.serializer(), response)
        assertFalse(encoded.contains("faker_elapsed_ms"), "non-streaming root must not carry faker_elapsed_ms: $encoded")
        assertFalse(encoded.contains("request_id"), "non-streaming root must not carry request_id: $encoded")
        assertEquals("end_turn", response.stop_reason)
    }

    @Test
    fun `error envelope is clean Anthropic with no echo`() = runTest {
        val raw = mapper.buildErrorEnvelope(type = "rate_limit_error", message = "Rate limit exceeded")
        assertTrue(raw.contains("\"type\":\"rate_limit_error\""), "error body must carry the type: $raw")
        assertFalse(raw.contains("faker_elapsed_ms"), "error body must not carry faker_elapsed_ms: $raw")
        assertFalse(raw.contains("request_id"), "error body must not echo request_id: $raw")
    }
}
