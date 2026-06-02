package com.faker.llm.adapter.openai.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * OpenAI-shaped error response: `{"error": {"message", "type", "code", "param"}}`.
 *
 * `code` and `param` are always emitted (even as `null`) to mirror the real API,
 * which is why `EncodeDefault.ALWAYS` is set on them.
 */
@Serializable
data class OpenAiErrorEnvelope(
    val error: OpenAiErrorBody,
    /** Total wall-clock ms spent inside the faker before this error envelope was written. */
    val faker_elapsed_ms: Long,
    /** Echo of the client-supplied request id header. Omitted when null (encodeDefaults=false). */
    val request_id: String? = null,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class OpenAiErrorBody(
    val message: String,
    val type: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val code: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val param: String? = null,
)
