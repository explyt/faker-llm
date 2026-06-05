package com.faker.llm.adapter.openai.dto

import com.faker.llm.app.AppliedTiming
import kotlinx.serialization.Serializable

/**
 * Body-carried faker echo on the response (faker-contract.md §8): the timing the faker
 * actually applied. It rides in the BODY — the final stream chunk, the non-streaming root,
 * or the error body — because the license tract strips custom HTTP headers.
 *
 * Reuses [AppliedTiming] (`{ttft_ms, itl_ms, total_ms}`) as its single field; `total_ms` is
 * the MEASURED wall-clock time inside the faker (request received → last byte), not a value
 * copied from the request directive.
 */
@Serializable
data class XFakerEcho(
    val applied_timing: AppliedTiming,
)
