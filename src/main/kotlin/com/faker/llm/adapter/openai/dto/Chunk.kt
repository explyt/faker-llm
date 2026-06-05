package com.faker.llm.adapter.openai.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Streaming SSE frame: `object = "chat.completion.chunk"`. Nullable delta fields are dropped
 * from the wire because `OpenAiJson` runs with `encodeDefaults = false`; the few fields that
 * MUST always appear (literal `object`, `index`, error `code`/`param`) opt in via
 * `@EncodeDefault(ALWAYS)`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatCompletionChunk(
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    /**
     * Final usage frame (faker-contract.md §6): after the chunk carrying `finish_reason`,
     * the faker emits one more chunk with `choices = []` and a populated `usage`. Null on
     * every intermediate frame — the `OpenAiJson` instance runs with `encodeDefaults = false`,
     * so a null `usage` is omitted from the wire.
     */
    val usage: Usage? = null,
    /**
     * Body-carried request-id echo (faker-contract.md §7). Emitted on the FIRST chunk so it
     * survives a stream truncated before the end; null (omitted) on other chunks.
     */
    val request_id: String? = null,
    /**
     * Body-carried applied-timing echo (faker-contract.md §8). Emitted on the FINAL usage chunk
     * (it is measured, meaningful only for a completed response); null (omitted) elsewhere.
     */
    val x_faker: XFakerEcho? = null,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ChunkChoice(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val index: Int = 0,
    val delta: ChunkDelta,
    val finish_reason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    val tool_calls: List<ToolCallDelta>? = null,
    /**
     * Reasoning / thinking stream channel. The faker-contract.md (example 2, `type=thinking`)
     * mandates the field name `reasoning_content` — vendors that consume the response key
     * off this exact spelling, not the older `reasoning` alias.
     */
    val reasoning_content: String? = null,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ToolCallDelta(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionDelta,
)

@Serializable
data class FunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)
