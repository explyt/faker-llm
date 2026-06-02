package com.faker.llm.domain

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Inclusive millisecond range `[min, max]`. */
@Serializable
data class RangeMs(val min: Long, val max: Long)

/** Inclusive integer range `[min, max]`. */
@Serializable
data class RangeInt(val min: Int, val max: Int)

/**
 * Picks a random value within `[min, max]` (inclusive).
 * Degenerate or inverted ranges (`min >= max`) return [min] instead of throwing.
 */
fun RangeMs.randomIn(): Long = randomIn(Random.Default)

/** Same as [randomIn] but with an injectable [Random] for deterministic callers (engine, tests). */
fun RangeMs.randomIn(random: Random): Long = if (min >= max) min else random.nextLong(min, max + 1)

/**
 * Picks a random value within `[min, max]` (inclusive).
 * Degenerate or inverted ranges (`min >= max`) return [min] instead of throwing.
 */
fun RangeInt.randomIn(): Int = randomIn(Random.Default)

/** Same as [randomIn] but with an injectable [Random] for deterministic callers (engine, tests). */
fun RangeInt.randomIn(random: Random): Int = if (min >= max) min else random.nextInt(min, max + 1)

/**
 * Realistic-timing knobs for the streaming engine.
 *
 * @param ttftMs time-to-first-token delay
 * @param interChunkMs delay between successive chunks
 * @param chunkSizeChars how many characters each emitted chunk carries
 */
@Serializable
data class TimingProfile(
    val ttftMs: RangeMs,
    val interChunkMs: RangeMs,
    val chunkSizeChars: RangeInt,
)
