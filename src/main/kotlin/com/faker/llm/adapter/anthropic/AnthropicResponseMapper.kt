package com.faker.llm.adapter.anthropic

import com.faker.llm.adapter.anthropic.dto.AnthropicContentBlock
import com.faker.llm.adapter.anthropic.dto.AnthropicErrorBody
import com.faker.llm.adapter.anthropic.dto.AnthropicErrorEnvelope
import com.faker.llm.adapter.anthropic.dto.AnthropicUsage
import com.faker.llm.adapter.anthropic.dto.ContentBlockDelta
import com.faker.llm.adapter.anthropic.dto.ContentBlockDeltaEvent
import com.faker.llm.adapter.anthropic.dto.ContentBlockStartEvent
import com.faker.llm.adapter.anthropic.dto.ContentBlockStopEvent
import com.faker.llm.adapter.anthropic.dto.MessageDeltaEvent
import com.faker.llm.adapter.anthropic.dto.MessageDeltaPayload
import com.faker.llm.adapter.anthropic.dto.MessageDeltaUsage
import com.faker.llm.adapter.anthropic.dto.MessageShell
import com.faker.llm.adapter.anthropic.dto.MessageStartEvent
import com.faker.llm.adapter.anthropic.dto.MessageStartUsage
import com.faker.llm.adapter.anthropic.dto.MessageStopEvent
import com.faker.llm.adapter.anthropic.dto.MessagesResponse
import com.faker.llm.domain.AbstractStreamEvent
import com.faker.llm.domain.FinishReason
import com.faker.llm.domain.MidStreamErrorKind
import com.faker.llm.domain.RequestContext
import com.faker.llm.domain.UsageStub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Writer
import kotlin.random.Random

/**
 * Maps engine events to Anthropic wire shapes.
 *
 * Streaming path is stateful inside a single `streamSse` call: tracks the current open
 * content block (text / thinking / tool_use) and its `index`, opening / closing as the
 * event type changes. Multi-block payloads (e.g. thinking followed by text) are encoded
 * as separate `content_block_*` runs, each with its own index.
 *
 * The response is CLEAN Anthropic with no faker echo (no `faker_elapsed_ms`, no `request_id`):
 * the contract is one-directional and the license tract strips any body extension anyway
 * (faker-contract.md §7), matching the OpenAI adapter. The directive rides in-band in the
 * message text and the client derives gateway overhead from the timing it requested.
 */
class AnthropicResponseMapper(
    private val random: Random = Random.Default,
    @Suppress("unused") private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    private val json = AnthropicJson.json

    // ---------------------------------------------------------------- non-streaming ---

    suspend fun buildNonStreaming(
        events: Flow<AbstractStreamEvent>,
        ctx: RequestContext,
        model: String,
    ): MessagesResponse {
        val collected = events.toList()

        val blocks = mutableListOf<AnthropicContentBlock>()
        val textBuf = StringBuilder()
        val thinkingBuf = StringBuilder()
        var currentToolName: String? = null
        var currentToolId: String? = null
        val currentToolArgs = StringBuilder()
        var end: AbstractStreamEvent.StreamEnd? = null

        fun flushText() {
            if (textBuf.isNotEmpty()) {
                blocks += AnthropicContentBlock.TextBlock(textBuf.toString())
                textBuf.clear()
            }
        }

        fun flushThinking() {
            if (thinkingBuf.isNotEmpty()) {
                blocks += AnthropicContentBlock.ThinkingBlock(thinkingBuf.toString())
                thinkingBuf.clear()
            }
        }

        for (event in collected) when (event) {
            is AbstractStreamEvent.StreamStart, is AbstractStreamEvent.StreamError -> Unit
            is AbstractStreamEvent.TextChunk -> {
                flushThinking()
                textBuf.append(event.delta)
            }
            is AbstractStreamEvent.ThinkingChunk -> {
                flushText()
                thinkingBuf.append(event.delta)
            }
            is AbstractStreamEvent.ToolCallStart -> {
                flushText(); flushThinking()
                currentToolName = event.toolName
                currentToolId = event.callId
                currentToolArgs.clear()
            }
            is AbstractStreamEvent.ToolCallArgsChunk -> currentToolArgs.append(event.delta)
            is AbstractStreamEvent.ToolCallEnd -> {
                val parsed = runCatching { json.parseToJsonElement(currentToolArgs.toString()) }
                    .getOrNull() as? JsonObject
                    ?: buildJsonObject { put("raw", currentToolArgs.toString()) }
                blocks += AnthropicContentBlock.ToolUseBlock(
                    id = currentToolId ?: event.callId,
                    name = currentToolName ?: "unknown",
                    input = parsed,
                )
                currentToolName = null
                currentToolId = null
                currentToolArgs.clear()
            }
            is AbstractStreamEvent.StreamEnd -> end = event
        }
        flushText(); flushThinking()

        val finishReason = end?.finishReason ?: FinishReason.Stop
        val usage = end?.usage ?: UsageStub(ctx.inspectableContent?.length ?: 0, 0)

        return MessagesResponse(
            id = newMessageId(),
            content = blocks,
            model = model,
            stop_reason = mapStopReason(finishReason),
            usage = AnthropicUsage(
                input_tokens = usage.promptChars / 4,
                output_tokens = usage.completionChars / 4,
            ),
        )
    }

    /** Builds a clean Anthropic error envelope JSON (no faker echo, per faker-contract.md §7). */
    fun buildErrorEnvelope(
        type: String,
        message: String,
    ): String =
        json.encodeToString(
            AnthropicErrorEnvelope.serializer(),
            AnthropicErrorEnvelope(
                error = AnthropicErrorBody(type = type, message = message),
            ),
        )

    // ----------------------------------------------------------------- streaming ---

    suspend fun streamSse(
        events: Flow<AbstractStreamEvent>,
        ctx: RequestContext,
        model: String,
        writer: Writer,
    ) {
        val state = StreamState(
            model = model,
            inputTokens = (ctx.inspectableContent?.length ?: 0) / 4,
        )
        var aborted = false

        events.collect { event ->
            if (aborted) return@collect
            when (event) {
                is AbstractStreamEvent.StreamStart -> writer.writeMessageStart(state)
                is AbstractStreamEvent.TextChunk -> {
                    ensureOpenBlock(writer, state, BlockType.Text)
                    writer.writeContentBlockDelta(state, ContentBlockDelta.TextDelta(event.delta))
                    state.outputChars += event.delta.length
                }
                is AbstractStreamEvent.ThinkingChunk -> {
                    ensureOpenBlock(writer, state, BlockType.Thinking)
                    writer.writeContentBlockDelta(state, ContentBlockDelta.ThinkingDelta(event.delta))
                    state.outputChars += event.delta.length
                }
                is AbstractStreamEvent.ToolCallStart -> {
                    closeOpenBlock(writer, state)
                    state.openToolBlock(writer, callId = event.callId, name = event.toolName)
                }
                is AbstractStreamEvent.ToolCallArgsChunk -> {
                    writer.writeContentBlockDelta(state, ContentBlockDelta.InputJsonDelta(event.delta))
                    state.outputChars += event.delta.length
                }
                is AbstractStreamEvent.ToolCallEnd -> closeOpenBlock(writer, state)
                is AbstractStreamEvent.StreamError -> {
                    // A mid-stream error means a torn stream — we do NOT emit terminators
                    // (no content_block_stop, no message_delta/stop).
                    when (event.kind) {
                        MidStreamErrorKind.AbruptDisconnect -> Unit
                        MidStreamErrorKind.ErrorEvent -> writer.writeErrorEvent(event.body.type, event.body.message)
                        MidStreamErrorKind.MalformedJson -> {
                            writer.write("data: {\n\n"); writer.flush()
                        }
                    }
                    aborted = true
                }
                is AbstractStreamEvent.StreamEnd -> {
                    closeOpenBlock(writer, state)
                    val outputTokens = event.usage.completionChars / 4
                    writer.writeMessageDelta(state, mapStopReason(event.finishReason), outputTokens)
                    writer.writeMessageStop()
                }
            }
        }
    }

    private fun ensureOpenBlock(writer: Writer, state: StreamState, type: BlockType) {
        if (state.openType == type) return
        closeOpenBlock(writer, state)
        state.openTextLikeBlock(writer, type)
    }

    private fun closeOpenBlock(writer: Writer, state: StreamState) {
        if (state.openType != null) {
            writer.writeAnthropicEvent(
                "content_block_stop",
                ContentBlockStopEvent.serializer(),
                ContentBlockStopEvent(index = state.currentIndex),
            )
            state.openType = null
            state.openCallId = null
        }
    }

    // -------------------------------------------------------------- helpers (state) ---

    private enum class BlockType { Text, Thinking, ToolUse }

    /** Per-call mutable state for the streaming path. */
    private inner class StreamState(
        val model: String,
        val inputTokens: Int,
    ) {
        val id: String = newMessageId()
        var currentIndex: Int = -1
        var openType: BlockType? = null
        var openCallId: String? = null
        var outputChars: Int = 0

        fun openTextLikeBlock(writer: Writer, type: BlockType) {
            currentIndex++
            openType = type
            val block: AnthropicContentBlock = when (type) {
                BlockType.Text -> AnthropicContentBlock.TextBlock("")
                BlockType.Thinking -> AnthropicContentBlock.ThinkingBlock("")
                BlockType.ToolUse -> error("openTextLikeBlock called for ToolUse")
            }
            writer.writeAnthropicEvent(
                "content_block_start",
                ContentBlockStartEvent.serializer(),
                ContentBlockStartEvent(
                    index = currentIndex,
                    content_block = block,
                ),
            )
        }

        fun openToolBlock(writer: Writer, callId: String, name: String) {
            currentIndex++
            openType = BlockType.ToolUse
            openCallId = callId
            writer.writeAnthropicEvent(
                "content_block_start",
                ContentBlockStartEvent.serializer(),
                ContentBlockStartEvent(
                    index = currentIndex,
                    content_block = AnthropicContentBlock.ToolUseBlock(
                        id = callId,
                        name = name,
                        input = JsonObject(emptyMap()),
                    ),
                ),
            )
        }
    }

    // ---------------------------------------------------------- helpers (writers) ---

    private fun Writer.writeMessageStart(state: StreamState) {
        writeAnthropicEvent(
            "message_start",
            MessageStartEvent.serializer(),
            MessageStartEvent(
                message = MessageShell(
                    id = state.id,
                    model = state.model,
                    usage = MessageStartUsage(input_tokens = state.inputTokens),
                ),
            ),
        )
    }

    private fun Writer.writeContentBlockDelta(state: StreamState, delta: ContentBlockDelta) {
        writeAnthropicEvent(
            "content_block_delta",
            ContentBlockDeltaEvent.serializer(),
            ContentBlockDeltaEvent(
                index = state.currentIndex,
                delta = delta,
            ),
        )
    }

    private fun Writer.writeMessageDelta(state: StreamState, stopReason: String, outputTokens: Int) {
        writeAnthropicEvent(
            "message_delta",
            MessageDeltaEvent.serializer(),
            MessageDeltaEvent(
                delta = MessageDeltaPayload(stop_reason = stopReason),
                usage = MessageDeltaUsage(output_tokens = outputTokens),
            ),
        )
    }

    private fun Writer.writeMessageStop() {
        writeAnthropicEvent(
            "message_stop",
            MessageStopEvent.serializer(),
            MessageStopEvent(),
        )
    }

    private fun Writer.writeErrorEvent(type: String, message: String) {
        writeAnthropicEvent(
            "error",
            AnthropicErrorEnvelope.serializer(),
            AnthropicErrorEnvelope(
                error = AnthropicErrorBody(type = type, message = message),
            ),
        )
    }

    private fun <T> Writer.writeAnthropicEvent(name: String, serializer: KSerializer<T>, payload: T) {
        write("event: $name\n")
        write("data: ")
        write(json.encodeToString(serializer, payload))
        write("\n\n")
        flush()
    }

    // ----------------------------------------------------------------- helpers (misc) ---

    private fun mapStopReason(reason: FinishReason): String = when (reason) {
        FinishReason.Stop -> "end_turn"
        FinishReason.Length -> "max_tokens"
        FinishReason.ToolCalls -> "tool_use"
        FinishReason.Error -> "end_turn"
    }

    private fun newMessageId(): String = buildString(ID_LENGTH + ID_PREFIX.length) {
        append(ID_PREFIX)
        repeat(ID_LENGTH) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
    }

    companion object {
        private const val ID_PREFIX = "msg_"
        private const val ID_LENGTH = 24
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
