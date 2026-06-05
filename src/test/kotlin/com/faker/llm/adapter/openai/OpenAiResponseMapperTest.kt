package com.faker.llm.adapter.openai

import com.faker.llm.domain.AbstractStreamEvent
import com.faker.llm.domain.FinishReason
import com.faker.llm.domain.UsageStub
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiResponseMapperTest {

    private val mapper = OpenAiResponseMapper(nowEpochSec = { 1_700_000_000L })

    private val requestId = "lt-9f2c1a7b3e4d5f60a1b2c3d4e5f60718"

    private fun streamEvents() = flowOf<AbstractStreamEvent>(
        AbstractStreamEvent.StreamStart,
        AbstractStreamEvent.TextChunk("word "),
        AbstractStreamEvent.StreamEnd(
            finishReason = FinishReason.Stop,
            usage = UsageStub(promptChars = 40, completionChars = 80),
        ),
    )

    @Test
    fun `thinking chunk uses reasoning_content not reasoning`() = runTest {
        val writer = StringWriter()
        val events = flowOf<AbstractStreamEvent>(
            AbstractStreamEvent.StreamStart,
            AbstractStreamEvent.ThinkingChunk("think "),
            AbstractStreamEvent.TextChunk("hello"),
            AbstractStreamEvent.StreamEnd(
                finishReason = FinishReason.Stop,
                usage = UsageStub(promptChars = 40, completionChars = 20),
            ),
        )

        mapper.streamSse(events, "test-model", writer, System.nanoTime(), requestId, 300L, 20L)

        val output = writer.toString()
        assertTrue(
            output.contains("\"reasoning_content\":\"think \""),
            "Expected reasoning_content field in thinking chunk, got:\n$output",
        )
        assertFalse(
            output.contains("\"reasoning\":"),
            "Old field name 'reasoning' must not appear in the stream",
        )
    }

    @Test
    fun `stream ends with finish_reason chunk then usage chunk then DONE`() = runTest {
        val writer = StringWriter()

        mapper.streamSse(streamEvents(), "test-model", writer, System.nanoTime(), requestId, 300L, 20L)

        val output = writer.toString()
        val frames = output.split("\n\n").filter { it.isNotBlank() }
        // Last three frames must be: finish_reason chunk, usage chunk, [DONE].
        val tail = frames.takeLast(3)
        assertEquals(3, tail.size, "Expected at least 3 trailing frames, got:\n$output")
        val (finishFrame, usageFrame, doneFrame) = Triple(tail[0], tail[1], tail[2])

        assertTrue(
            finishFrame.contains("\"finish_reason\":\"stop\""),
            "Penultimate-to-last frame must carry finish_reason: stop. Was: $finishFrame",
        )
        assertFalse(
            finishFrame.contains("\"usage\""),
            "finish_reason frame must not contain usage. Was: $finishFrame",
        )

        assertTrue(
            usageFrame.contains("\"choices\":[]"),
            "Usage frame must have empty choices. Was: $usageFrame",
        )
        assertTrue(
            usageFrame.contains("\"usage\":"),
            "Usage frame must include usage field. Was: $usageFrame",
        )
        // Per contract §6 completion_tokens reflects the *actual* number of tokens streamed
        // (UsageStub.completionChars / 4 = 80 / 4 = 20).
        assertTrue(
            usageFrame.contains("\"completion_tokens\":20"),
            "Usage frame completion_tokens must equal actual streamed tokens (20). Was: $usageFrame",
        )

        assertEquals("data: [DONE]", doneFrame, "Last frame must be data: [DONE]")
    }

    @Test
    fun `request_id rides only in the first chunk`() = runTest {
        val writer = StringWriter()

        mapper.streamSse(streamEvents(), "test-model", writer, System.nanoTime(), requestId, 300L, 20L)

        val output = writer.toString()
        val frames = output.split("\n\n").filter { it.isNotBlank() }
        // §7: request_id must appear in the FIRST chunk so it survives a truncated stream.
        assertTrue(
            frames.first().contains("\"request_id\":\"$requestId\""),
            "First chunk must carry request_id. Was: ${frames.first()}",
        )
        // It must appear exactly once across the whole stream (not repeated on every frame).
        val occurrences = Regex(Regex.escape("\"request_id\":\"$requestId\"")).findAll(output).count()
        assertEquals(1, occurrences, "request_id must appear exactly once (first chunk). Output:\n$output")
    }

    @Test
    fun `final usage chunk carries x_faker applied_timing with measured total`() = runTest {
        val writer = StringWriter()
        // Planned ttft/itl are echoed verbatim; total_ms is MEASURED (~0 here, since the test
        // flow emits with no real delay) and must NOT be a copied directive value.
        mapper.streamSse(streamEvents(), "test-model", writer, System.nanoTime(), requestId, 300L, 20L)

        val output = writer.toString()
        val usageFrame = output.split("\n\n").filter { it.isNotBlank() }
            .first { it.contains("\"usage\"") }

        assertTrue(
            usageFrame.contains("\"x_faker\":{\"applied_timing\":"),
            "Usage frame must carry x_faker.applied_timing. Was: $usageFrame",
        )
        assertTrue(
            usageFrame.contains("\"ttft_ms\":300") && usageFrame.contains("\"itl_ms\":20"),
            "applied_timing must echo planned ttft/itl. Was: $usageFrame",
        )
        // The intermediate (content) frames must NOT carry the echo.
        val contentFrame = output.split("\n\n").first { it.contains("\"content\":\"word \"") }
        assertFalse(contentFrame.contains("x_faker"), "Content frames must not carry x_faker. Was: $contentFrame")
    }

    @Test
    fun `non-streaming root carries request_id and measured applied_timing`() = runTest {
        val response = mapper.buildNonStreaming(
            events = streamEvents(),
            model = "test-model",
            requestStartNanos = System.nanoTime(),
            requestId = requestId,
            plannedTtftMs = 300L,
            plannedItlMs = 20L,
        )
        assertEquals(requestId, response.request_id)
        val applied = response.x_faker?.applied_timing
        assertTrue(applied != null, "non-streaming root must carry x_faker.applied_timing")
        assertEquals(300L, applied.ttft_ms)
        assertEquals(20L, applied.itl_ms)
        // Measured, not copied: the flow completes instantly so total_ms stays tiny.
        assertTrue(applied.total_ms < 1000L, "total_ms must be measured wall-clock, was ${applied.total_ms}")
    }

    @Test
    fun `non-streaming usage completion_tokens reflects actual streamed tokens`() = runTest {
        val response = mapper.buildNonStreaming(
            events = flowOf<AbstractStreamEvent>(
                AbstractStreamEvent.StreamStart,
                AbstractStreamEvent.TextChunk("hello"),
                AbstractStreamEvent.StreamEnd(
                    finishReason = FinishReason.Stop,
                    usage = UsageStub(promptChars = 40, completionChars = 80),
                ),
            ),
            model = "test-model",
            requestStartNanos = System.nanoTime(),
            requestId = requestId,
            plannedTtftMs = 300L,
            plannedItlMs = 20L,
        )
        assertEquals(20, response.usage.completion_tokens, "completion_tokens = completionChars / 4 = 80 / 4")
        assertEquals(10, response.usage.prompt_tokens)
        assertEquals(30, response.usage.total_tokens)
    }

    @Test
    fun `non-streaming thinking exposes reasoning_content field not think wrapper`() = runTest {
        val response = mapper.buildNonStreaming(
            events = flowOf<AbstractStreamEvent>(
                AbstractStreamEvent.StreamStart,
                AbstractStreamEvent.ThinkingChunk("Step 1: reasoning. "),
                AbstractStreamEvent.TextChunk("the answer"),
                AbstractStreamEvent.StreamEnd(
                    finishReason = FinishReason.Stop,
                    usage = UsageStub(promptChars = 40, completionChars = 40),
                ),
            ),
            model = "test-model",
            requestStartNanos = System.nanoTime(),
            requestId = requestId,
            plannedTtftMs = 300L,
            plannedItlMs = 20L,
        )
        val message = response.choices.single().message
        // §6: reasoning rides in its own field; the client reads message.reasoning_content.
        assertEquals("Step 1: reasoning. ", message.reasoning_content)
        assertEquals("the answer", message.content)
        assertFalse(message.content?.contains("<think>") == true, "must not fold reasoning into <think> wrapper")
    }

    @Test
    fun `error envelope carries request_id and zero applied_timing`() = runTest {
        val raw = mapper.buildErrorEnvelope(
            message = "Rate limit exceeded",
            type = "rate_limit_error",
            code = "rate_limit_exceeded",
            requestId = requestId,
        )
        assertTrue(raw.contains("\"request_id\":\"$requestId\""), "error body must echo request_id. Was: $raw")
        assertTrue(
            raw.contains("\"x_faker\":{\"applied_timing\":{\"ttft_ms\":0,\"itl_ms\":0,\"total_ms\":0}}"),
            "error body must carry applied_timing 0/0/0 (contract §8). Was: $raw",
        )
    }
}
