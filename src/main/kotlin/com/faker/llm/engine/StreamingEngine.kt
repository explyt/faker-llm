package com.faker.llm.engine

import com.faker.llm.domain.AbstractStreamEvent
import com.faker.llm.domain.PoolEntry
import com.faker.llm.domain.RequestContext
import kotlinx.coroutines.flow.Flow

/**
 * Provider-agnostic streaming engine: plays a [PoolEntry] back as a cold
 * `Flow<AbstractStreamEvent>` with realistic TTFT / inter-chunk delays.
 *
 * Adapters subscribe to this flow and map abstract events to their native SSE shape.
 * `HttpErrorEntry` MUST NOT be passed here — adapters handle pre-stream HTTP failures
 * directly. Implementations are expected to throw `IllegalArgumentException` if violated.
 */
interface StreamingEngine {
    fun execute(entry: PoolEntry, ctx: RequestContext): Flow<AbstractStreamEvent>
}
