package com.faker.llm.engine

import kotlin.random.Random

/**
 * Lazily splits the receiver into chunks of random length within [range] (inclusive on both
 * ends). The last chunk may be shorter than `range.first` if not enough characters remain.
 *
 * Returns a [Sequence] (not a `List`) to keep allocation pressure low under 1000 RPS —
 * each chunk is materialized only when the consumer iterates.
 *
 * @throws IllegalArgumentException when the range starts at zero or below
 */
internal fun String.chunkByRange(range: IntRange, random: Random): Sequence<String> = sequence {
    require(range.first > 0) { "chunk size lower bound must be > 0, was ${range.first}" }
    if (isEmpty()) return@sequence
    var pos = 0
    while (pos < length) {
        val size = if (range.first >= range.last) range.first
        else random.nextInt(range.first, range.last + 1)
        val end = minOf(pos + size, length)
        yield(substring(pos, end))
        pos = end
    }
}
