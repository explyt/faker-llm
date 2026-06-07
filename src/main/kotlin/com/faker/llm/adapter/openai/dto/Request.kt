package com.faker.llm.adapter.openai.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI `POST /v1/chat/completions` request. Unknown fields are ignored via the adapter's
 * [com.faker.llm.adapter.openai.OpenAiJson] (`ignoreUnknownKeys = true`), so extensions like
 * `temperature`, `top_p`, `seed`, `response_format` etc. are tolerated without modeling.
 *
 * There is NO body-carried faker channel: the license tract strips the request body, so the
 * directive travels in-band inside `messages[].content` as a `[[faker:...]]` marker
 * (parsed by [com.faker.llm.routing.policies.PromptDirectivePolicy]). The wire stays clean OpenAI.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDef>? = null,
    val stream: Boolean = false,
)

@Serializable
data class ChatMessage(
    val role: String,
    /** String or array of content parts (multimodal). [JsonElement] keeps both shapes verbatim. */
    val content: JsonElement? = null,
    val tool_call_id: String? = null,
    val tool_calls: JsonElement? = null,
)

@Serializable
data class ToolDef(
    val type: String,
    val function: FunctionDef,
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null,
)
