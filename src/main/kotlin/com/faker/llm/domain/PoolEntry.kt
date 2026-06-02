package com.faker.llm.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * One entry in the response pool. Weighted random selection at request time.
 *
 * Two flavors:
 * - [SuccessEntry] — a normal (possibly streaming) response, possibly with a mid-stream error.
 * - [HttpErrorEntry] — a pre-stream HTTP failure (429/500/...), no SSE at all.
 *
 * JSON discriminator is `"kind"` (not the default `"type"`) to avoid colliding
 * with [ResponsePart]'s discriminator when both nest inside the same document.
 *
 * @property id stable identifier (used by `[[faker:force_id:...]]` directives)
 * @property weight relative weight for weighted random pick; must be > 0
 * @property requiresTools entry applies only to requests that carry a non-empty `tools` array
 * @property tag optional category label (e.g. `"tool_call"`, `"long_reasoning"`),
 *   used by `[[faker:force_tag:...]]` and selector-level filtering
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface PoolEntry {
    val id: String
    val weight: Double
    val requiresTools: Boolean
    val tag: String?
}

/** A normal completion entry. */
@Serializable
@SerialName("success")
data class SuccessEntry(
    override val id: String,
    override val weight: Double,
    override val requiresTools: Boolean = false,
    override val tag: String? = null,
    val parts: List<ResponsePart>,
    val timing: TimingProfile,
    val finishReason: FinishReason = FinishReason.Stop,
    val midStreamError: MidStreamError? = null,
) : PoolEntry

/** A pre-stream HTTP error entry. Adapter returns [status] with provider-shaped [errorBody]. */
@Serializable
@SerialName("http_error")
data class HttpErrorEntry(
    override val id: String,
    override val weight: Double,
    override val requiresTools: Boolean = false,
    override val tag: String? = null,
    val status: Int,
    val errorBody: ErrorBody,
    /** Optional artificial latency before the error is returned. */
    val preResponseDelayMs: RangeMs = RangeMs(0, 0),
) : PoolEntry
