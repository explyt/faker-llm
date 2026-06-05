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
    /** Body-carried request-id echo in the error root (faker-contract.md §7). Omitted when null. */
    val request_id: String? = null,
    /**
     * Body-carried applied-timing echo (faker-contract.md §8). On the error path the stub answers
     * synthetically with no upstream work, so this is `0/0/0` and the whole E2E counts as gateway
     * overhead. Required on every HTTP error response (the client flags a missing echo).
     */
    val x_faker: XFakerEcho? = null,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class OpenAiErrorBody(
    val message: String,
    val type: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val code: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val param: String? = null,
)
