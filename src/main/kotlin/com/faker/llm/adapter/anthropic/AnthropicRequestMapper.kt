package com.faker.llm.adapter.anthropic

import com.faker.llm.adapter.anthropic.dto.AnthropicMessage
import com.faker.llm.adapter.anthropic.dto.MessagesRequest
import com.faker.llm.domain.RequestContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Builds [RequestContext] from a parsed Anthropic request.
 *
 * `inspectableContent` concatenates (joined by `\n`):
 *  - `system` field (string OR array of `{type:"text", text:"..."}` blocks)
 *  - all `messages` with `role = "user"` (content string OR array of text blocks)
 *
 * `tools[].name` is the Anthropic equivalent of OpenAI's `function.name`.
 */
object AnthropicRequestMapper {

    fun toContext(request: MessagesRequest): RequestContext {
        val toolNames = request.tools.orEmpty().map { it.name }

        val parts = mutableListOf<String>()
        extractText(request.system)?.let { if (it.isNotEmpty()) parts += it }
        for (message in request.messages) {
            if (message.role != "user") continue
            extractText(message.content)?.let { if (it.isNotEmpty()) parts += it }
        }

        return RequestContext(
            hasTools = toolNames.isNotEmpty(),
            toolNames = toolNames,
            stream = request.stream,
            model = request.model,
            inspectableContent = parts.joinToString("\n").ifEmpty { null },
            // Directive comes from the in-band marker (PromptDirectivePolicy), not a header —
            // parity with the OpenAI adapter; RequestContext.directiveHeader stays null.
        )
    }

    /** Returns text content from a `content`/`system` value: string-form or text-array. */
    private fun extractText(raw: JsonElement?): String? = when (raw) {
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
