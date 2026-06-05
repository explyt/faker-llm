package com.faker.llm.routing.policies

import com.faker.llm.domain.FakerDirective
import com.faker.llm.domain.FakerDirectiveError
import com.faker.llm.domain.RequestContext
import com.faker.llm.routing.RoutingDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyDirectivePolicyTest {

    private val policy = BodyDirectivePolicy()

    private fun ctx(directive: FakerDirective?) = RequestContext(
        hasTools = false,
        toolNames = emptyList(),
        stream = true,
        model = "x",
        inspectableContent = null,
        directive = directive,
    )

    @Test
    fun `absent directive passes through`() {
        assertNull(policy.decide(ctx(null)))
    }

    @Test
    fun `error directive maps to synthetic http error with requested status`() {
        val decision = policy.decide(ctx(FakerDirective(type = "error", error = FakerDirectiveError(http_status = 429))))
        assertEquals(RoutingDecision.SyntheticHttpError(429), decision)
    }

    @Test
    fun `error directive without status defaults to 500`() {
        val decision = policy.decide(ctx(FakerDirective(type = "error")))
        assertEquals(RoutingDecision.SyntheticHttpError(500), decision)
    }

    @Test
    fun `streamed and edge types map to synthetic behavior`() {
        for (type in listOf("normal", "thinking", "tool_call", "empty", "timeout")) {
            val directive = FakerDirective(type = type)
            val decision = policy.decide(ctx(directive))
            assertTrue(
                decision is RoutingDecision.SyntheticBehavior && decision.directive === directive,
                "type=$type must map to SyntheticBehavior carrying the directive, got $decision",
            )
        }
    }

    @Test
    fun `unknown type passes through`() {
        assertNull(policy.decide(ctx(FakerDirective(type = "totally_unknown"))))
    }
}
