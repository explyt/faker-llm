package com.faker.llm.adapter.openai.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Non-streaming response: `object = "chat.completion"`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatCompletionResponse(
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
    /** Body-carried request-id echo in the root (faker-contract.md §7). */
    val request_id: String? = null,
    /** Body-carried applied-timing echo in the root (faker-contract.md §8). */
    val x_faker: XFakerEcho? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Choice(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val index: Int = 0,
    val message: AssistantMessage,
    val finish_reason: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AssistantMessage(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val role: String = "assistant",
    val content: String? = null,
    /**
     * Non-streaming reasoning channel (faker-contract.md §6). Mirrors the streaming
     * `delta.reasoning_content`; omitted (null) when the response has no thinking block.
     */
    val reasoning_content: String? = null,
    val tool_calls: List<ToolCallResponse>? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ToolCallResponse(
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    /** Stringified JSON — OpenAI peculiarity, NOT a nested object. */
    val arguments: String,
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int,
)
