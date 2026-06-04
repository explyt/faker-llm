package com.faker.llm.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FakerDirectiveTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses normal directive with timing`() {
        val raw = """{"type":"normal","timing":{"ttft_ms":300,"itl_ms":20,"total_ms":2000}}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        assertEquals("normal", directive.type)
        val timing = assertNotNull(directive.timing)
        assertEquals(300L, timing.ttft_ms)
        assertEquals(20L, timing.itl_ms)
        assertEquals(2000L, timing.total_ms)
    }

    @Test
    fun `parses thinking directive with min_tokens`() {
        val raw = """{"type":"thinking","timing":{"ttft_ms":300,"itl_ms":20,"total_ms":2000},"thinking":{"min_tokens":20}}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        assertEquals("thinking", directive.type)
        assertEquals(20, directive.thinking?.min_tokens)
    }

    @Test
    fun `parses tool_call directive with name`() {
        val raw = """{"type":"tool_call","timing":{"ttft_ms":300},"tool_call":{"name":"get_weather"}}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        assertEquals("tool_call", directive.type)
        assertEquals("get_weather", directive.tool_call?.name)
    }

    @Test
    fun `parses error directive with http_status`() {
        val raw = """{"type":"error","error":{"http_status":429}}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        assertEquals("error", directive.type)
        assertEquals(429, directive.error?.http_status)
    }

    @Test
    fun `parses timeout directive without timing`() {
        val raw = """{"type":"timeout"}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        assertEquals("timeout", directive.type)
        assertNull(directive.timing)
    }

    @Test
    fun `unknown fields are tolerated`() {
        // v1-only fields like tokens / seed / error.code must not break parsing.
        val raw = """{"type":"normal","tokens":{"output":200},"seed":42,"error":{"http_status":500,"code":"foo","message":"bar"}}"""
        val directive = json.decodeFromString<FakerDirective>(raw)
        assertEquals("normal", directive.type)
        // No `tokens`/`seed` fields on FakerDirective anymore — they just get dropped.
    }
}
