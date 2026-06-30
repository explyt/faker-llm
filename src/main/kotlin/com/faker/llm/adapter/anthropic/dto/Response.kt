package com.faker.llm.adapter.anthropic.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Non-streaming response. Polymorphic on `AnthropicContentBlock` (discriminator: `type`).
 *
 * `stop_sequence` is always serialized — even when null — to match the real API,
 * which always emits it (so clients can rely on its presence for branching).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessagesResponse(
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "message",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val role: String = "assistant",
    val content: List<AnthropicContentBlock>,
    val model: String,
    val stop_reason: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val stop_sequence: String? = null,
    val usage: AnthropicUsage,
)

/**
 * Sealed content block. Discriminator (`type`) is kotlinx default — both this hierarchy and
 * [ContentBlockDelta] use `"type"`, no `classDiscriminator` override needed.
 */
@Serializable
sealed interface AnthropicContentBlock {

    @Serializable
    @SerialName("text")
    data class TextBlock(val text: String) : AnthropicContentBlock

    @Serializable
    @SerialName("thinking")
    data class ThinkingBlock(val thinking: String) : AnthropicContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUseBlock(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : AnthropicContentBlock
}

@Serializable
data class AnthropicUsage(
    val input_tokens: Int,
    val output_tokens: Int,
)
