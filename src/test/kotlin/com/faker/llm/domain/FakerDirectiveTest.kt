package com.faker.llm.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FakerDirectiveTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses tokens output`() {
        val raw = """{"type":"normal","tokens":{"output":200}}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        assertEquals("normal", directive.type)
        val tokens = assertNotNull(directive.tokens)
        assertEquals(200, tokens.output)
    }

    @Test
    fun `absent tokens object is null`() {
        val raw = """{"type":"normal"}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        assertNull(directive.tokens)
    }

    @Test
    fun `tokens with absent output stays null`() {
        val raw = """{"type":"normal","tokens":{}}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        val tokens = assertNotNull(directive.tokens)
        assertNull(tokens.output)
    }

    @Test
    fun `parses thinking with tokens output together`() {
        val raw = """{"type":"thinking","thinking":{"min_tokens":20},"tokens":{"output":80}}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        assertEquals("thinking", directive.type)
        assertEquals(20, directive.thinking?.min_tokens)
        assertEquals(80, directive.tokens?.output)
    }
}
