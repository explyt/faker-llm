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

        mapper.streamSse(events, model = "test-model", writer = writer, requestStartNanos = System.nanoTime())

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
        val events = flowOf<AbstractStreamEvent>(
            AbstractStreamEvent.StreamStart,
            AbstractStreamEvent.TextChunk("word "),
            AbstractStreamEvent.StreamEnd(
                finishReason = FinishReason.Stop,
                usage = UsageStub(promptChars = 40, completionChars = 80),
            ),
        )

        mapper.streamSse(
            events,
            model = "test-model",
            writer = writer,
            requestStartNanos = System.nanoTime(),
        )

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
        // Per v2 contract §6 completion_tokens reflects the *actual* number of tokens streamed
        // (UsageStub.completionChars / 4 = 80 / 4 = 20).
        assertTrue(
            usageFrame.contains("\"completion_tokens\":20"),
            "Usage frame completion_tokens must equal actual streamed tokens (20). Was: $usageFrame",
        )

        assertEquals("data: [DONE]", doneFrame, "Last frame must be data: [DONE]")
    }

    @Test
    fun `non-streaming usage completion_tokens reflects actual streamed tokens`() = runTest {
        val events = flowOf<AbstractStreamEvent>(
            AbstractStreamEvent.StreamStart,
            AbstractStreamEvent.TextChunk("hello"),
            AbstractStreamEvent.StreamEnd(
                finishReason = FinishReason.Stop,
                usage = UsageStub(promptChars = 40, completionChars = 80),
            ),
        )
        val response = mapper.buildNonStreaming(
            events = events,
            model = "test-model",
            requestStartNanos = System.nanoTime(),
        )
        assertEquals(20, response.usage.completion_tokens, "completion_tokens = completionChars / 4 = 80 / 4")
        assertEquals(10, response.usage.prompt_tokens)
        assertEquals(30, response.usage.total_tokens)
    }
}
