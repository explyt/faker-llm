package com.faker.llm.app

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * Wall-clock timer attached to each inbound request. The route handler stamps
 * `System.nanoTime()` at its very first instruction (before `receiveText`), and every
 * downstream emitter computes `faker_elapsed_ms` against that anchor — for the FIRST
 * frame this is the effective TTFT; for subsequent frames in a stream the emitter swaps
 * the anchor for `lastEmitNanos` so the field becomes inter-chunk delta.
 *
 * This is INFRASTRUCTURE-OVERHEAD instrumentation, not modeled latency: load tests can
 * subtract our reported ms from wall-clock RTT and see how much the network + Ktor
 * pipeline cost them.
 */
internal val RequestStartNanosKey: AttributeKey<Long> = AttributeKey("faker.requestStartNanos")

/** Stamp the start nano-time at the very first instruction of a route handler. */
internal fun ApplicationCall.markRequestStart() {
    attributes.put(RequestStartNanosKey, System.nanoTime())
}

/**
 * Returns the start nano-time previously stamped by [markRequestStart], or `System.nanoTime()`
 * as a safe fallback for handlers (StatusPages, unhandled paths) that never called the marker.
 * The fallback elapsed will be ~0ms — which is honest: we have no earlier anchor to report.
 */
internal fun ApplicationCall.requestStartedNanos(): Long =
    attributes.getOrNull(RequestStartNanosKey) ?: System.nanoTime()

/** Wall-clock elapsed ms from [startNanos] until now. Always non-negative. */
internal fun elapsedMsSince(startNanos: Long): Long =
    ((System.nanoTime() - startNanos).coerceAtLeast(0L)) / 1_000_000L

/**
 * Mutable anchor used by streaming mappers to compute INTER-CHUNK deltas.
 * Initialized to the request-start nano; first frame's `faker_elapsed_ms` is therefore
 * the TTFT. Each frame writer must call [tick] to update the anchor AFTER reading the delta.
 */
internal class EmitTimer(initialNanos: Long) {
    private var lastNanos: Long = initialNanos

    /** Reads the delta in ms since the previous tick, then advances the anchor. */
    fun nextElapsedMs(): Long {
        val now = System.nanoTime()
        val delta = ((now - lastNanos).coerceAtLeast(0L)) / 1_000_000L
        lastNanos = now
        return delta
    }
}
