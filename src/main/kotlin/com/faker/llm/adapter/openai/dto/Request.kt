package com.faker.llm.adapter.openai.dto

import com.faker.llm.domain.FakerDirective
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI `POST /v1/chat/completions` request. Unknown fields are ignored via the adapter's
 * [com.faker.llm.adapter.openai.OpenAiJson] (`ignoreUnknownKeys = true`), so extensions like
 * `temperature`, `top_p`, `seed`, `response_format` etc. are tolerated without modeling.
 *
 * The faker contract rides in the BODY (the license tract strips custom HTTP headers):
 *  - [request_id] — top-level client-generated id the faker MUST echo back in the response body.
 *  - [x_faker] — `{ "directive": { ... } }` carrying the per-request directive (see
 *    [com.faker.llm.domain.FakerDirective]). Both are absent in `real` mode and tolerated as
 *    unknown fields by compatible upstreams.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDef>? = null,
    val stream: Boolean = false,
    val request_id: String? = null,
    val x_faker: XFakerRequest? = null,
)

/** Body-carried faker extension on the request: the directive selecting the response shape. */
@Serializable
data class XFakerRequest(
    val directive: FakerDirective? = null,
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
