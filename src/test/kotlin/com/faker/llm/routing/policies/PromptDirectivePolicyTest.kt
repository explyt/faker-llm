package com.faker.llm.routing.policies

import com.faker.llm.domain.RequestContext
import com.faker.llm.routing.RoutingDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptDirectivePolicyTest {

    private val policy = PromptDirectivePolicy()

    private fun ctx(content: String?) = RequestContext(
        hasTools = false,
        toolNames = emptyList(),
        stream = true,
        model = "x",
        inspectableContent = content,
    )

    private fun decide(content: String?) = policy.decide(ctx(content))

    // --- the five synthesized-behavior types ------------------------------------------------

    @Test
    fun `normal marker maps to synthetic behavior carrying the timing`() {
        val decision = decide("lorem ipsum [[faker:type=normal;ttft=200;itl=20;total=2200]] tail")
        assertTrue(decision is RoutingDecision.SyntheticBehavior)
        val d = decision.directive
        assertEquals("normal", d.type)
        assertEquals(200L, d.timing?.ttft_ms)
        assertEquals(20L, d.timing?.itl_ms)
        assertEquals(2200L, d.timing?.total_ms)
    }

    @Test
    fun `thinking marker maps to synthetic behavior`() {
        val decision = decide("[[faker:type=thinking;ttft=200;itl=20;total=2200]]")
        assertTrue(decision is RoutingDecision.SyntheticBehavior && decision.directive.type == "thinking")
    }

    @Test
    fun `tool_call marker maps to synthetic behavior with first-token timing only`() {
        val decision = decide("[[faker:type=tool_call;ttft=300]]")
        assertTrue(decision is RoutingDecision.SyntheticBehavior)
        assertEquals("tool_call", (decision as RoutingDecision.SyntheticBehavior).directive.type)
        assertEquals(300L, decision.directive.timing?.ttft_ms)
    }

    @Test
    fun `empty marker maps to synthetic behavior`() {
        val decision = decide("[[faker:type=empty;ttft=300]]")
        assertTrue(decision is RoutingDecision.SyntheticBehavior && decision.directive.type == "empty")
    }

    @Test
    fun `timeout marker maps to synthetic behavior`() {
        val decision = decide("[[faker:type=timeout]]")
        assertTrue(decision is RoutingDecision.SyntheticBehavior && decision.directive.type == "timeout")
    }

    @Test
    fun `replay marker carries the payload and pacing, maps to synthetic behavior`() {
        // base64url(no-pad) payload — alphabet [A-Za-z0-9-_], free of `;`/`=`/`]]`, so it rides as
        // one marker value. Must round-trip byte-identically with the client's DirectiveMarker.
        val decision = decide("recorded prompt [[faker:type=replay;ttft=10;itl=1;payload=eyJjb250ZW50IjoiaGkifQ]] tail")
        assertTrue(decision is RoutingDecision.SyntheticBehavior)
        val d = (decision as RoutingDecision.SyntheticBehavior).directive
        assertEquals("replay", d.type)
        assertEquals("eyJjb250ZW50IjoiaGkifQ", d.replay?.payload)
        assertEquals(10L, d.timing?.ttft_ms)
        assertEquals(1L, d.timing?.itl_ms)
    }

    // --- error ------------------------------------------------------------------------------

    @Test
    fun `error marker maps to synthetic http error with the requested status`() {
        assertEquals(RoutingDecision.SyntheticHttpError(429), decide("[[faker:type=error;status=429]]"))
    }

    @Test
    fun `error marker without status defaults to 400`() {
        assertEquals(RoutingDecision.SyntheticHttpError(400), decide("[[faker:type=error]]"))
    }

    // --- edge cases (parse grammar parity with the client reference) -------------------------

    @Test
    fun `no inspectable content returns null`() {
        assertNull(decide(null))
    }

    @Test
    fun `no marker in the text returns null`() {
        assertNull(decide("just an ordinary prompt with no directive"))
    }

    @Test
    fun `marker without a type is not a directive`() {
        // type missing → ordinary pool answer (null), even though other keys are present.
        assertNull(decide("[[faker:ttft=200;itl=20;total=2200]]"))
    }

    @Test
    fun `unknown type passes through`() {
        assertNull(decide("[[faker:type=totally_unknown;ttft=10]]"))
    }

    @Test
    fun `non-numeric values parse to zero`() {
        // ttft=abc → 0, and the only numeric-bearing key being junk means no timing object at all.
        val decision = decide("[[faker:type=normal;ttft=abc;itl=xyz;total=nope]]")
        assertTrue(decision is RoutingDecision.SyntheticBehavior)
        assertNull((decision as RoutingDecision.SyntheticBehavior).directive.timing)
    }

    @Test
    fun `non-numeric error status falls back to the default 400`() {
        assertEquals(RoutingDecision.SyntheticHttpError(400), decide("[[faker:type=error;status=oops]]"))
    }

    @Test
    fun `keys are case-insensitive`() {
        val decision = decide("[[faker:TYPE=normal;TTFT=200;ITL=20;Total=2200]]")
        assertTrue(decision is RoutingDecision.SyntheticBehavior)
        assertEquals(2200L, (decision as RoutingDecision.SyntheticBehavior).directive.timing?.total_ms)
    }

    @Test
    fun `unknown keys are ignored`() {
        val decision = decide("[[faker:type=normal;ttft=200;itl=20;total=2200;seed=99;foo=bar]]")
        assertTrue(decision is RoutingDecision.SyntheticBehavior && decision.directive.type == "normal")
    }

    @Test
    fun `first marker wins when several are present`() {
        // The first [[faker:...]] span is the directive; the later one is ignored entirely.
        val decision = decide("[[faker:type=error;status=503]] ... [[faker:type=normal;ttft=200;itl=20;total=2200]]")
        assertEquals(RoutingDecision.SyntheticHttpError(503), decision)
    }

    @Test
    fun `unterminated marker returns null`() {
        assertNull(decide("prefix [[faker:type=normal;ttft=200 no closing bracket"))
    }

    @Test
    fun `first closing bracket pair ends the marker body`() {
        // The body is `type=normal;total=20`; the trailing `]] tail]]` is outside the directive.
        val decision = decide("[[faker:type=normal;total=20]] tail]]")
        assertTrue(decision is RoutingDecision.SyntheticBehavior)
        val d = (decision as RoutingDecision.SyntheticBehavior).directive
        assertEquals("normal", d.type)
        assertEquals(20L, d.timing?.total_ms)
    }

    @Test
    fun `empty marker body is not a directive`() {
        // `[[faker:]]` has no `type` → ordinary pool answer (null).
        assertNull(decide("[[faker:]]"))
    }

    @Test
    fun `status on a non-error type does not turn it into an error`() {
        // A stray status on `normal` is parsed (parity with the client) but the type still wins:
        // the decision is a synthesized behavior, not a SyntheticHttpError.
        val decision = decide("[[faker:type=normal;status=429;ttft=10;itl=5;total=100]]")
        assertTrue(
            decision is RoutingDecision.SyntheticBehavior && decision.directive.type == "normal",
            "non-error type with a stray status must stay SyntheticBehavior, got $decision",
        )
    }
}
