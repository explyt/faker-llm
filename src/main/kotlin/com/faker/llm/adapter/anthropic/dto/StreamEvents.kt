package com.faker.llm.adapter.anthropic.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Anthropic streaming SSE events. Each frame on the wire is two lines:
 *   `event: <name>\n`
 *   `data: <json>\n\n`
 *
 * The `type` literal inside `data` mirrors the SSE `event:` name. We pin literals via
 * `@EncodeDefault(ALWAYS)` since `AnthropicJson` runs with `encodeDefaults = false`.
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageStartEvent(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "message_start",
    val message: MessageShell,
    /** Wall-clock ms since the previous emitted event; for `message_start` this equals TTFT. */
    val faker_elapsed_ms: Long,
)

/**
 * Shell carried inside `message_start`. The real API uses
 * `usage.output_tokens = 1` even before any content has streamed — we mirror that.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageShell(
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "message",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val role: String = "assistant",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val content: List<AnthropicContentBlock> = emptyList(),
    val model: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val stop_reason: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val stop_sequence: String? = null,
    val usage: MessageStartUsage,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageStartUsage(
    val input_tokens: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val output_tokens: Int = 1,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContentBlockStartEvent(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "content_block_start",
    val index: Int,
    val content_block: AnthropicContentBlock,
    /** Wall-clock ms since the previous emitted event. */
    val faker_elapsed_ms: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContentBlockDeltaEvent(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "content_block_delta",
    val index: Int,
    val delta: ContentBlockDelta,
    /** Wall-clock ms since the previous emitted event. */
    val faker_elapsed_ms: Long,
)

/**
 * Delta payload — sealed on the kotlinx-default `"type"` discriminator.
 * Matches names exactly: `text_delta`, `thinking_delta`, `input_json_delta`.
 */
@Serializable
sealed interface ContentBlockDelta {

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val text: String) : ContentBlockDelta

    @Serializable
    @SerialName("thinking_delta")
    data class ThinkingDelta(val thinking: String) : ContentBlockDelta

    /** Stringified-JSON fragment for tool-call arguments (mirrors OpenAI `arguments` chunks). */
    @Serializable
    @SerialName("input_json_delta")
    data class InputJsonDelta(val partial_json: String) : ContentBlockDelta
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContentBlockStopEvent(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "content_block_stop",
    val index: Int,
    /** Wall-clock ms since the previous emitted event. */
    val faker_elapsed_ms: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageDeltaEvent(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "message_delta",
    val delta: MessageDeltaPayload,
    val usage: MessageDeltaUsage,
    /** Wall-clock ms since the previous emitted event. */
    val faker_elapsed_ms: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageDeltaPayload(
    val stop_reason: String,
    /** Always emitted — even as `null` — to match the real API. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val stop_sequence: String? = null,
)

@Serializable
data class MessageDeltaUsage(val output_tokens: Int)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageStopEvent(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "message_stop",
    /** Wall-clock ms since the previous emitted event. */
    val faker_elapsed_ms: Long,
)
