package com.faker.llm.adapter.openai

import com.faker.llm.adapter.openai.dto.ChatCompletionResponse
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

        mapper.streamSse(events, "test-model", writer)

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

        mapper.streamSse(streamEvents(), "test-model", writer)

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
    fun `stream carries no faker echo on any frame`() = runTest {
        val writer = StringWriter()

        mapper.streamSse(streamEvents(), "test-model", writer)

        val output = writer.toString()
        // The contract is one-directional: no request_id and no x_faker anywhere in the stream.
        assertFalse(output.contains("request_id"), "stream must not carry request_id. Output:\n$output")
        assertFalse(output.contains("x_faker"), "stream must not carry x_faker. Output:\n$output")
    }

    @Test
    fun `non-streaming root is clean OpenAI with no echo`() = runTest {
        val response = mapper.buildNonStreaming(
            events = streamEvents(),
            model = "test-model",
        )
        val encoded = OpenAiJson.json.encodeToString(ChatCompletionResponse.serializer(), response)
        assertFalse(encoded.contains("request_id"), "non-streaming root must not carry request_id. Was: $encoded")
        assertFalse(encoded.contains("x_faker"), "non-streaming root must not carry x_faker. Was: $encoded")
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
        )
        val message = response.choices.single().message
        // §6: reasoning rides in its own field; the client reads message.reasoning_content.
        assertEquals("Step 1: reasoning. ", message.reasoning_content)
        assertEquals("the answer", message.content)
        assertFalse(message.content?.contains("<think>") == true, "must not fold reasoning into <think> wrapper")
    }

    @Test
    fun `error envelope is clean OpenAI with no echo`() = runTest {
        val raw = mapper.buildErrorEnvelope(
            message = "Rate limit exceeded",
            type = "rate_limit_error",
            code = "rate_limit_exceeded",
        )
        assertTrue(raw.contains("\"type\":\"rate_limit_error\""), "error body must carry the type. Was: $raw")
        assertTrue(raw.contains("\"code\":\"rate_limit_exceeded\""), "error body must carry the code. Was: $raw")
        assertFalse(raw.contains("request_id"), "error body must not echo request_id. Was: $raw")
        assertFalse(raw.contains("x_faker"), "error body must not carry x_faker. Was: $raw")
    }
}
