package com.faker.llm.engine

import kotlin.random.Random

/**
 * Lazily splits the receiver into chunks of random length within [range] (inclusive on both
 * ends). The last chunk may be shorter than `range.first` if not enough characters remain.
 *
 * A chunk boundary NEVER splits a UTF-16 surrogate pair: a non-BMP character (e.g. an emoji
 * 🔴) is two `Char`s, and emitting them in separate chunks would put a lone surrogate on the
 * wire, which JSON encoders/decoders replace with U+FFFD — corrupting the echoed text. So a
 * boundary that would land between a high and low surrogate is nudged past the low surrogate.
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
        var end = minOf(pos + size, length)
        // Keep a surrogate pair whole: if the boundary sits between a high and low surrogate,
        // extend by one to include the low surrogate in this chunk.
        if (end < length && this@chunkByRange[end - 1].isHighSurrogate() && this@chunkByRange[end].isLowSurrogate()) {
            end += 1
        }
        yield(substring(pos, end))
        pos = end
    }
}
