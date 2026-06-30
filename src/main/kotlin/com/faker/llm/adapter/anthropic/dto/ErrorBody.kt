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
)

@Serializable
data class AnthropicErrorBody(val type: String, val message: String)
