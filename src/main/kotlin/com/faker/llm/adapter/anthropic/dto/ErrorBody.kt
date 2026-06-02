package com.faker.llm.adapter.anthropic.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Anthropic-shaped error envelope: `{"type": "error", "error": {"type", "message"}}`.
 *
 * Outer `type` literal must always be emitted (mirrors the real API).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnthropicErrorEnvelope(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "error",
    val error: AnthropicErrorBody,
    /** Total wall-clock ms spent inside the faker before this error envelope was written. */
    val faker_elapsed_ms: Long,
    /** Echo of the client-supplied request id header. Omitted when null (encodeDefaults=false). */
    val request_id: String? = null,
)

@Serializable
data class AnthropicErrorBody(val type: String, val message: String)
