package com.faker.llm.adapter.openai

import com.faker.llm.adapter.openai.dto.AssistantMessage
import com.faker.llm.adapter.openai.dto.ChatCompletionChunk
import com.faker.llm.adapter.openai.dto.ChatCompletionResponse
import com.faker.llm.adapter.openai.dto.Choice
import com.faker.llm.adapter.openai.dto.ChunkChoice
import com.faker.llm.adapter.openai.dto.ChunkDelta
import com.faker.llm.adapter.openai.dto.FunctionCall
import com.faker.llm.adapter.openai.dto.FunctionDelta
import com.faker.llm.adapter.openai.dto.OpenAiErrorBody
import com.faker.llm.adapter.openai.dto.OpenAiErrorEnvelope
import com.faker.llm.adapter.openai.dto.ToolCallDelta
import com.faker.llm.adapter.openai.dto.ToolCallResponse
import com.faker.llm.adapter.openai.dto.Usage
import com.faker.llm.adapter.openai.dto.XFakerEcho
import com.faker.llm.app.AppliedTiming
import com.faker.llm.app.elapsedMsSince
import com.faker.llm.domain.AbstractStreamEvent
import com.faker.llm.domain.FinishReason
import com.faker.llm.domain.MidStreamErrorKind
import com.faker.llm.domain.UsageStub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import java.io.Writer
import kotlin.random.Random

/**
 * Maps the engine's provider-agnostic `Flow<AbstractStreamEvent>` to OpenAI wire shapes.
 *
 * Faker contract echoes ride in the response BODY (the license tract strips headers):
 *  - `request_id` on the FIRST stream chunk / non-streaming root / error root (§7);
 *  - `x_faker.applied_timing` on the FINAL usage chunk / non-streaming root / error root (§8),
 *    where `total_ms` is the MEASURED wall-clock from [requestStartNanos] to the last byte,
 *    while `ttft_ms`/`itl_ms` are the planned pace passed in by the route handler.
 */
class OpenAiResponseMapper(
    private val random: Random = Random.Default,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    private val json = OpenAiJson.json

    // ---------------------------------------------------------------- non-streaming ---

    suspend fun buildNonStreaming(
        events: Flow<AbstractStreamEvent>,
        model: String,
        requestStartNanos: Long,
        requestId: String?,
        plannedTtftMs: Long,
        plannedItlMs: Long,
    ): ChatCompletionResponse {
        val collected = events.toList()

        val textBuf = StringBuilder()
        val thinkingBuf = StringBuilder()
        val toolCalls = mutableListOf<ToolCallResponse>()
        var currentToolName: String? = null
        var currentToolId: String? = null
        val currentToolArgs = StringBuilder()
        var end: AbstractStreamEvent.StreamEnd? = null

        for (event in collected) when (event) {
            is AbstractStreamEvent.StreamStart, is AbstractStreamEvent.StreamError -> Unit
            is AbstractStreamEvent.TextChunk -> textBuf.append(event.delta)
            is AbstractStreamEvent.ThinkingChunk -> thinkingBuf.append(event.delta)
            is AbstractStreamEvent.ToolCallStart -> {
                currentToolName = event.toolName
                currentToolId = event.callId
                currentToolArgs.clear()
            }
            is AbstractStreamEvent.ToolCallArgsChunk -> currentToolArgs.append(event.delta)
            is AbstractStreamEvent.ToolCallEnd -> {
                toolCalls += ToolCallResponse(
                    id = currentToolId ?: event.callId,
                    function = FunctionCall(
                        name = currentToolName ?: "unknown",
                        arguments = currentToolArgs.toString(),
                    ),
                )
                currentToolName = null
                currentToolId = null
                currentToolArgs.clear()
            }
            is AbstractStreamEvent.StreamEnd -> end = event
        }

        val finishReason = end?.finishReason ?: FinishReason.Stop
        val usage = end?.usage ?: UsageStub(0, 0)

        // Reasoning rides in its own `message.reasoning_content` field, mirroring the streaming
        // `delta.reasoning_content` channel — this is what clients key off to detect a thinking
        // block in non-streaming mode (faker-contract.md §5/§6; reference stub does the same).
        val content = textBuf.toString().takeIf { it.isNotEmpty() }
        val reasoning = thinkingBuf.toString().takeIf { it.isNotEmpty() }

        return ChatCompletionResponse(
            id = newCompletionId(),
            created = nowEpochSec(),
            model = model,
            choices = listOf(
                Choice(
                    message = AssistantMessage(
                        content = content,
                        reasoning_content = reasoning,
                        tool_calls = toolCalls.takeIf { it.isNotEmpty() },
                    ),
                    finish_reason = mapFinishReason(finishReason),
                ),
            ),
            usage = toUsage(usage),
            request_id = requestId,
            // total_ms is MEASURED: collecting the cold flow above ran the engine's ttft + itl
            // delays, so wall-clock elapsed reflects the real upstream time (faker-contract.md §8).
            x_faker = XFakerEcho(
                AppliedTiming(
                    ttft_ms = plannedTtftMs,
                    itl_ms = plannedItlMs,
                    total_ms = elapsedMsSince(requestStartNanos),
                ),
            ),
        )
    }

    /**
     * Builds an error envelope JSON carrying the body echo: `request_id` (§7) and
     * `x_faker.applied_timing = 0/0/0` (§8 — the faker returns errors synthetically, so the
     * whole E2E counts as gateway overhead). The client flags a missing applied-timing echo.
     *
     * @param code optional `error.code` (e.g. `rate_limit_exceeded`); always emitted as
     *   a JSON field even when `null` to mirror the real OpenAI wire shape
     *   (see [OpenAiErrorBody] `@EncodeDefault(ALWAYS)`).
     */
    fun buildErrorEnvelope(
        message: String,
        type: String,
        code: String? = null,
        requestId: String? = null,
    ): String =
        json.encodeToString(
            OpenAiErrorEnvelope.serializer(),
            OpenAiErrorEnvelope(
                error = OpenAiErrorBody(message = message, type = type, code = code),
                request_id = requestId,
                x_faker = XFakerEcho(AppliedTiming(ttft_ms = 0L, itl_ms = 0L, total_ms = 0L)),
            ),
        )

    // ----------------------------------------------------------------- streaming ---

    /**
     * Streams events as SSE into [writer]. Honors [MidStreamErrorKind] semantics:
     *  - `AbruptDisconnect` — stop writing immediately (no terminator, no `[DONE]`)
     *  - `ErrorEvent`       — emit `event: error` frame, then stop (no `[DONE]`)
     *  - `MalformedJson`    — emit broken frame `data: {\n\n`, then stop (no `[DONE]`)
     *
     * A clean stream finishes with `data: [DONE]\n\n` after the final `StreamEnd`-chunk.
     */
    suspend fun streamSse(
        events: Flow<AbstractStreamEvent>,
        model: String,
        writer: Writer,
        requestStartNanos: Long,
        requestId: String?,
        plannedTtftMs: Long,
        plannedItlMs: Long,
    ) {
        val id = newCompletionId()
        val created = nowEpochSec()
        var firstFrameWritten = false
        var aborted = false

        // request_id rides on the FIRST emitted frame so it survives a stream truncated
        // before the end (faker-contract.md §7); subsequent frames omit it.
        fun frameRequestId(): String? =
            if (firstFrameWritten) null else requestId.also { firstFrameWritten = true }

        fun writeDelta(delta: ChunkDelta) {
            writer.writeDataFrame(
                ChatCompletionChunk(
                    id = id,
                    created = created,
                    model = model,
                    choices = listOf(ChunkChoice(delta = delta)),
                    request_id = frameRequestId(),
                ),
            )
        }

        events.collect { event ->
            if (aborted) return@collect
            when (event) {
                is AbstractStreamEvent.StreamStart -> writeDelta(ChunkDelta(role = "assistant"))
                is AbstractStreamEvent.TextChunk -> writeDelta(ChunkDelta(content = event.delta))
                is AbstractStreamEvent.ThinkingChunk -> writeDelta(ChunkDelta(reasoning_content = event.delta))
                is AbstractStreamEvent.ToolCallStart -> writeDelta(
                    ChunkDelta(
                        tool_calls = listOf(
                            ToolCallDelta(
                                id = event.callId,
                                type = "function",
                                function = FunctionDelta(name = event.toolName),
                            ),
                        ),
                    ),
                )
                is AbstractStreamEvent.ToolCallArgsChunk -> writeDelta(
                    ChunkDelta(
                        tool_calls = listOf(ToolCallDelta(function = FunctionDelta(arguments = event.delta))),
                    ),
                )
                is AbstractStreamEvent.ToolCallEnd -> Unit // OpenAI signals end via finish_reason
                is AbstractStreamEvent.StreamError -> {
                    when (event.kind) {
                        MidStreamErrorKind.AbruptDisconnect -> Unit
                        MidStreamErrorKind.ErrorEvent -> {
                            val payload = json.encodeToString(
                                OpenAiErrorEnvelope.serializer(),
                                OpenAiErrorEnvelope(
                                    error = OpenAiErrorBody(
                                        message = event.body.message,
                                        type = event.body.type,
                                    ),
                                ),
                            )
                            writer.write("event: error\ndata: ")
                            writer.write(payload)
                            writer.write("\n\n")
                            writer.flush()
                        }
                        MidStreamErrorKind.MalformedJson -> {
                            writer.write("data: {\n\n")
                            writer.flush()
                        }
                    }
                    aborted = true
                }
                is AbstractStreamEvent.StreamEnd -> {
                    // finish_reason frame — no usage, no echo (faker-contract.md §6/§10).
                    val finalChunk = ChatCompletionChunk(
                        id = id,
                        created = created,
                        model = model,
                        choices = listOf(
                            ChunkChoice(
                                delta = ChunkDelta(),
                                finish_reason = mapFinishReason(event.finishReason),
                            ),
                        ),
                        request_id = frameRequestId(),
                    )
                    writer.write("data: ")
                    writer.write(json.encodeToString(ChatCompletionChunk.serializer(), finalChunk))
                    writer.write("\n\n")
                    // Final usage frame with `choices=[]`, the populated `usage`, and the
                    // applied-timing echo. total_ms is MEASURED here (request → last byte), not
                    // copied from the directive (faker-contract.md §8).
                    val usageChunk = ChatCompletionChunk(
                        id = id,
                        created = created,
                        model = model,
                        choices = emptyList(),
                        usage = toUsage(event.usage),
                        x_faker = XFakerEcho(
                            AppliedTiming(
                                ttft_ms = plannedTtftMs,
                                itl_ms = plannedItlMs,
                                total_ms = elapsedMsSince(requestStartNanos),
                            ),
                        ),
                    )
                    writer.write("data: ")
                    writer.write(json.encodeToString(ChatCompletionChunk.serializer(), usageChunk))
                    writer.write("\n\n")
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                }
            }
        }
    }

    // ---------------------------------------------------------------------- helpers ---

    private fun Writer.writeDataFrame(chunk: ChatCompletionChunk) {
        write("data: ")
        write(json.encodeToString(ChatCompletionChunk.serializer(), chunk))
        write("\n\n")
        flush()
    }

    /**
     * Maps the engine's [UsageStub] to the wire [Usage]. Per faker-contract 2.md §6
     * `completion_tokens` MUST reflect the number of tokens actually streamed (for
     * `thinking` requests this also includes the reasoning prefix).
     */
    private fun toUsage(stub: UsageStub): Usage {
        val promptTokens = stub.promptChars / 4
        val completionTokens = stub.completionChars / 4
        return Usage(
            prompt_tokens = promptTokens,
            completion_tokens = completionTokens,
            total_tokens = promptTokens + completionTokens,
        )
    }

    private fun mapFinishReason(reason: FinishReason): String = when (reason) {
        FinishReason.Stop -> "stop"
        FinishReason.Length -> "length"
        FinishReason.ToolCalls -> "tool_calls"
        FinishReason.Error -> "stop"
    }

    private fun newCompletionId(): String = buildString(ID_LENGTH + ID_PREFIX.length) {
        append(ID_PREFIX)
        repeat(ID_LENGTH) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
    }

    companion object {
        private const val ID_PREFIX = "chatcmpl-"
        private const val ID_LENGTH = 29
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
