package com.faker.llm.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkingTest {

    @Test
    fun `chunking never splits a surrogate pair`() {
        // Each emoji is a UTF-16 surrogate pair (2 Chars). With chunk size 1 the naive splitter
        // would put a lone surrogate in its own chunk → corruption on the wire. The guard must
        // keep every pair whole and reassemble exactly.
        val s = "a🔴b🏗c📊d🔴🏗"
        val chunks = s.chunkByRange(1..1, Random(0)).toList()
        assertEquals(s, chunks.joinToString(""), "reassembly must equal the original")
        for (c in chunks) {
            assertTrue(c.isNotEmpty(), "no empty chunk")
            assertTrue(!c.last().isHighSurrogate(), "chunk must not end with a lone high surrogate: '$c'")
            assertTrue(!c.first().isLowSurrogate(), "chunk must not start with a lone low surrogate: '$c'")
        }
    }

    @Test
    fun `ascii chunking is unaffected`() {
        val s = "hello world"
        assertEquals(s, s.chunkByRange(4..4, Random(0)).toList().joinToString(""))
    }
}
