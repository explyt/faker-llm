package com.faker.llm.domain

import kotlinx.serialization.Serializable

/** Provider-agnostic completion outcome; adapters map it to their own wire value. */
@Serializable
enum class FinishReason { Stop, Length, ToolCalls, Error }

/**
 * Raw character counts. Adapters convert these into provider token fields
 * (e.g. `prompt_tokens` / `input_tokens`), typically via the `chars / 4` heuristic.
 */
@Serializable
data class UsageStub(val promptChars: Int, val completionChars: Int)
