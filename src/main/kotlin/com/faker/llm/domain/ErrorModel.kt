package com.faker.llm.domain

import kotlinx.serialization.Serializable

/**
 * Provider-agnostic error pair. Each adapter maps it into its native error envelope:
 * - OpenAI: `{ error: { type, message, code } }`
 * - Anthropic: `{ type: "error", error: { type, message } }`
 */
@Serializable
data class ErrorBody(val type: String, val message: String)

/** What flavor of failure should interrupt an otherwise-successful stream. */
@Serializable
enum class MidStreamErrorKind {
    /** Tear the TCP connection without sending any terminator (`[DONE]` / `message_stop`). */
    AbruptDisconnect,

    /** Emit an SSE `event: error` carrying [ErrorBody] and close cleanly. */
    ErrorEvent,

    /** Send a single broken JSON frame, then close. Useful to test client robustness. */
    MalformedJson,
}

/**
 * Tells the engine to inject a failure after [afterChunks] content chunks have been emitted.
 * `afterChunks = 0` means the failure fires before the first chunk (rare but legal).
 */
@Serializable
data class MidStreamError(
    val afterChunks: Int,
    val kind: MidStreamErrorKind,
)
