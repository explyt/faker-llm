package com.faker.llm.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One semantic chunk of a fake completion. A [com.faker.llm.domain.SuccessEntry]
 * is a list of these, played back in order by the streaming engine.
 *
 * Sealed for exhaustive `when` and polymorphic JSON (`type` discriminator).
 */
@Serializable
sealed interface ResponsePart {

    /** Plain assistant text. */
    @Serializable
    @SerialName("text")
    data class Text(val content: String) : ResponsePart

    /** OpenAI `reasoning` / Anthropic `thinking` block. Adapters wire it to the right channel. */
    @Serializable
    @SerialName("thinking")
    data class Thinking(val content: String) : ResponsePart

    /**
     * Tool call arguments template. The tool *name* is normally NOT stored here — the
     * engine picks it at runtime from [RequestContext.toolNames]. Pool entries that carry
     * [ToolCall] parts must set `requiresTools = true` so the selector filters them out for
     * tool-less requests.
     *
     * [toolName] is an explicit override used by the `replay` directive to echo the exact
     * recorded tool call: when non-null the engine uses it verbatim instead of picking from
     * the context (and the `requiresTools`/`toolNames` precondition does not apply).
     *
     * The template supports placeholders like `${random:int:1:100}` or
     * `${request:tool_name}`; resolution is implemented in the engine, not here.
     */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(val argsTemplate: JsonObject, val toolName: String? = null) : ResponsePart
}
