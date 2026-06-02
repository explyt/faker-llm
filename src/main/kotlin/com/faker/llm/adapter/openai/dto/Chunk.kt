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
    /** Wall-clock ms since the previous emitted frame; for the first frame this equals TTFT. */
    val faker_elapsed_ms: Long,
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
    /** OpenAI `o1`-style reasoning stream channel. */
    val reasoning: String? = null,
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
