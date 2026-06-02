package com.faker.llm.app

import com.faker.llm.domain.ResponsePart
import com.faker.llm.domain.SuccessEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** HTTP response header carrying the timing the faker actually applied to this response. */
const val APPLIED_TIMING_HEADER = "X-Faker-Applied-Timing"

/**
 * Compact JSON shape echoed back in [APPLIED_TIMING_HEADER]. See faker-contract.md:
 * `{"ttft_ms":N,"itl_ms":N,"total_ms":N}` — load-test scripts use it to compute
 * gateway overhead as `E2E - total_ms`.
 */
@Serializable
data class AppliedTiming(
    val ttft_ms: Long,
    val itl_ms: Long,
    val total_ms: Long,
)

/** Encode to the compact JSON string that goes into the header value. */
fun AppliedTiming.toHeaderValue(json: Json): String =
    json.encodeToString(AppliedTiming.serializer(), this)

/**
 * Non-streaming + error path: ITL is meaningless (no chunks), only TTFT and total apply.
 * Caller passes wall-clock TTFT and total; we keep ITL=0 to be explicit.
 */
fun fromElapsed(ttft: Long, total: Long): AppliedTiming =
    AppliedTiming(ttft_ms = ttft, itl_ms = 0L, total_ms = total)

/**
 * Streaming compromise: the header MUST be written before the first body byte, so we
 * have no choice but to estimate the totals up front. Formula uses the **expected**
 * value of each range (engine picks random in [min, max] on every emit, so average
 * is the unbiased estimator):
 *  - `ttft_ms`  = `avg(timing.ttftMs)`
 *  - `itl_ms`   = `avg(timing.interChunkMs)`
 *  - `n_chunks` = `total_chars / avg(timing.chunkSizeChars)`
 *  - `total_ms` = `ttft_ms + n_chunks * itl_ms`
 *
 * Empty parts collapse to `total_ms = ttft_ms`. The estimate is centered on the
 * expected wall-clock; client `overhead = E2E - total_ms` stays positive because
 * the client-side E2E always carries network + gateway latency on top of our
 * server-side wall-clock, so the difference is always > 0 even when our estimate
 * is slightly off the actual server-side number (faker-contract.md, section
 * "Что именно входит в `total_ms`").
 */
fun estimateForStreaming(entry: SuccessEntry): AppliedTiming {
    val ttft = avg(entry.timing.ttftMs.min, entry.timing.ttftMs.max)
    val itl = avg(entry.timing.interChunkMs.min, entry.timing.interChunkMs.max)
    val totalChars = entry.parts.sumOf { part ->
        when (part) {
            is ResponsePart.Text -> part.content.length
            is ResponsePart.Thinking -> part.content.length
            // Tool-call args JSON length is a good proxy for chunkable payload size.
            is ResponsePart.ToolCall -> part.argsTemplate.toString().length
        }
    }
    val avgChunkSize = maxOf(
        1L,
        avg(entry.timing.chunkSizeChars.min.toLong(), entry.timing.chunkSizeChars.max.toLong()),
    )
    val totalMs = if (totalChars == 0) ttft else ttft + (totalChars.toLong() / avgChunkSize) * itl
    return AppliedTiming(ttft_ms = ttft, itl_ms = itl, total_ms = totalMs)
}

/** Midpoint of an inclusive `[min, max]` range. Same formula the engine's `randomIn` averages to. */
private fun avg(min: Long, max: Long): Long = (min + max) / 2L
