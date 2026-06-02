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
import com.faker.llm.app.EmitTimer
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
 * Every emitted frame carries `faker_elapsed_ms` (see DTOs): for the first SSE frame this is
 * the effective TTFT measured from [requestStartNanos]; for subsequent frames it is the
 * wall-clock delta from the previous frame. Non-streaming responses report total elapsed
 * from request received until the response object was built.
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
        outputTokensLimit: Int? = null,
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

        // Reasoning-in-non-streaming is a documented limitation (Task 07): wrap thinking
        // in <think>...</think> and prepend it to content when present.
        val content = buildString {
            if (thinkingBuf.isNotEmpty()) {
                append("<think>").append(thinkingBuf).append("</think>\n")
            }
            append(textBuf)
        }.takeIf { it.isNotEmpty() }

        return ChatCompletionResponse(
            id = newCompletionId(),
            created = nowEpochSec(),
            model = model,
            choices = listOf(
                Choice(
                    message = AssistantMessage(
                        content = content,
                        tool_calls = toolCalls.takeIf { it.isNotEmpty() },
                    ),
                    finish_reason = mapFinishReason(finishReason),
                ),
            ),
            usage = toUsage(usage, outputTokensLimit),
            faker_elapsed_ms = elapsedMsSince(requestStartNanos),
        )
    }

    /**
     * Builds an error envelope JSON with `faker_elapsed_ms` from the request anchor.
     *
     * @param code optional `error.code` (e.g. `rate_limit_exceeded`); always emitted as
     *   a JSON field even when `null` to mirror the real OpenAI wire shape
     *   (see [OpenAiErrorBody] `@EncodeDefault(ALWAYS)`).
     */
    fun buildErrorEnvelope(
        message: String,
        type: String,
        requestStartNanos: Long,
        code: String? = null,
        requestId: String? = null,
    ): String =
        json.encodeToString(
            OpenAiErrorEnvelope.serializer(),
            OpenAiErrorEnvelope(
                error = OpenAiErrorBody(message = message, type = type, code = code),
                faker_elapsed_ms = elapsedMsSince(requestStartNanos),
                request_id = requestId,
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
        outputTokensLimit: Int? = null,
    ) {
        val id = newCompletionId()
        val created = nowEpochSec()
        val timer = EmitTimer(requestStartNanos)
        var aborted = false

        events.collect { event ->
            if (aborted) return@collect
            when (event) {
                is AbstractStreamEvent.StreamStart -> {
                    writer.writeDataFrame(chunk(id, created, model, ChunkDelta(role = "assistant"), timer))
                }
                is AbstractStreamEvent.TextChunk -> {
                    writer.writeDataFrame(chunk(id, created, model, ChunkDelta(content = event.delta), timer))
                }
                is AbstractStreamEvent.ThinkingChunk -> {
                    writer.writeDataFrame(chunk(id, created, model, ChunkDelta(reasoning_content = event.delta), timer))
                }
                is AbstractStreamEvent.ToolCallStart -> {
                    val delta = ChunkDelta(
                        tool_calls = listOf(
                            ToolCallDelta(
                                id = event.callId,
                                type = "function",
                                function = FunctionDelta(name = event.toolName),
                            ),
                        ),
                    )
                    writer.writeDataFrame(chunk(id, created, model, delta, timer))
                }
                is AbstractStreamEvent.ToolCallArgsChunk -> {
                    val delta = ChunkDelta(
                        tool_calls = listOf(
                            ToolCallDelta(function = FunctionDelta(arguments = event.delta)),
                        ),
                    )
                    writer.writeDataFrame(chunk(id, created, model, delta, timer))
                }
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
                                    faker_elapsed_ms = timer.nextElapsedMs(),
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
                        faker_elapsed_ms = timer.nextElapsedMs(),
                    )
                    writer.write("data: ")
                    writer.write(json.encodeToString(ChatCompletionChunk.serializer(), finalChunk))
                    writer.write("\n\n")
                    // faker-contract.md §1 (example 1): after `finish_reason` we emit a
                    // standalone usage frame with `choices=[]` and the populated `usage`,
                    // unconditional on the request's `stream_options.include_usage`.
                    val usageChunk = ChatCompletionChunk(
                        id = id,
                        created = created,
                        model = model,
                        choices = emptyList(),
                        faker_elapsed_ms = timer.nextElapsedMs(),
                        usage = toUsage(event.usage, outputTokensLimit),
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

    private fun chunk(
        id: String,
        created: Long,
        model: String,
        delta: ChunkDelta,
        timer: EmitTimer,
    ): ChatCompletionChunk = ChatCompletionChunk(
        id = id,
        created = created,
        model = model,
        choices = listOf(ChunkChoice(delta = delta)),
        faker_elapsed_ms = timer.nextElapsedMs(),
    )

    private fun Writer.writeDataFrame(chunk: ChatCompletionChunk) {
        write("data: ")
        write(json.encodeToString(ChatCompletionChunk.serializer(), chunk))
        write("\n\n")
        flush()
    }

    /**
     * Maps the engine's [UsageStub] to the wire [Usage]. When [outputTokensLimit] is set
     * (i.e. the client sent `tokens.output`), `completion_tokens` is FORCED to that exact
     * value — the contract requires `usage.completion_tokens == tokens.output`, regardless
     * of how much text the engine actually streamed (text may have been capped via
     * `EntryOutputCap`, but the reported count must reflect the directive).
     */
    private fun toUsage(stub: UsageStub, outputTokensLimit: Int? = null): Usage {
        val promptTokens = stub.promptChars / 4
        val completionTokens = outputTokensLimit ?: (stub.completionChars / 4)
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
