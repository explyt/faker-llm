package com.faker.llm.adapter.openai

import com.faker.llm.adapter.openai.dto.ChatCompletionRequest
import com.faker.llm.adapter.openai.dto.ChatMessage
import com.faker.llm.domain.RequestContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses an OpenAI request into the provider-agnostic [RequestContext]. The only non-trivial
 * piece is `inspectableContent`: real OpenAI accepts both a plain string and a multimodal array
 * of `{ "type": "text", "text": "..." }` parts, and the router needs them concatenated.
 */
object OpenAiRequestMapper {

    private val INSPECTABLE_ROLES = setOf("user", "system")

    fun toContext(request: ChatCompletionRequest, directiveHeader: String? = null): RequestContext {
        val toolNames = request.tools.orEmpty().map { it.function.name }
        val inspectable = request.messages
            .filter { it.role in INSPECTABLE_ROLES }
            .mapNotNull { extractText(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifEmpty { null }

        return RequestContext(
            hasTools = toolNames.isNotEmpty(),
            toolNames = toolNames,
            stream = request.stream,
            model = request.model,
            inspectableContent = inspectable,
            directiveHeader = directiveHeader,
        )
    }

    /** Returns text content from a message: string-form `content`, or text parts of an array. */
    private fun extractText(message: ChatMessage): String? = when (val raw = message.content) {
        null -> null
        is JsonPrimitive -> raw.contentOrNull
        is JsonArray -> raw.asSequence()
            .filterIsInstance<JsonObject>()
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
            .joinToString("\n")
            .ifEmpty { null }

        else -> null
    }
}
