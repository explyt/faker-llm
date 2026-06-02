package com.faker.llm.adapter.anthropic.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Anthropic `POST /v1/messages` request. Unknown fields are swallowed by
 * [com.faker.llm.adapter.anthropic.AnthropicJson]; `max_tokens` is required by the real API
 * but the faker is lenient (Task 08 caveat: documented deviation, no validation here).
 */
@Serializable
data class MessagesRequest(
    val model: String,
    val max_tokens: Int? = null,
    val messages: List<AnthropicMessage>,
    /** String, or array of `{type:"text", text:"..."}` blocks. */
    val system: JsonElement? = null,
    val tools: List<AnthropicToolDef>? = null,
    val stream: Boolean = false,
    /** `{"type": "enabled", "budget_tokens": N}` when extended thinking is requested. */
    val thinking: JsonElement? = null,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    /** String, or array of content blocks (text / image / tool_use / tool_result). */
    val content: JsonElement,
)

@Serializable
data class AnthropicToolDef(
    val name: String,
    val description: String? = null,
    val input_schema: JsonElement? = null,
)
