package com.faker.llm.adapter.openai

import com.faker.llm.engine.DefaultStreamingEngine
import com.faker.llm.pool.PoolSelector
import com.faker.llm.routing.CompositeRequestRouter
import com.faker.llm.routing.policies.PromptDirectivePolicy
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end wiring tests for the OpenAI surface: parse → route (PromptDirectivePolicy) → branch.
 * The pool is intentionally EMPTY — every case here is driven by an in-band directive that
 * short-circuits the selector, so reaching the (empty) pool would itself be a wiring bug.
 *
 * The migration's invariants asserted here: the directive comes only from the message text, and
 * the response is CLEAN OpenAI with no `request_id` / `x_faker` echo on any path.
 */
class OpenAiRoutesTest {

    private val modelId = "faker"

    private fun body(content: String, stream: Boolean = false): String =
        """{"model":"$modelId","stream":$stream,"messages":[{"role":"user","content":"$content"}]}"""

    private fun testRoutes(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            routing {
                openAiRoutes(
                    selector = PoolSelector(emptyList()),
                    router = CompositeRequestRouter(listOf(PromptDirectivePolicy())),
                    engine = DefaultStreamingEngine(),
                    modelId = modelId,
                )
            }
        }
        block(client)
    }

    @Test
    fun `error directive short-circuits to the requested status with a clean envelope`() = testRoutes { client ->
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(body("hi [[faker:type=error;status=429]]"))
        }
        assertEquals(429, response.status.value, "status must equal the requested one")
        val text = response.bodyAsText()
        assertTrue(text.contains("\"type\":\"rate_limit_error\""), "error envelope must carry the type. Was: $text")
        assertFalse(text.contains("request_id"), "error body must not echo request_id. Was: $text")
        assertFalse(text.contains("x_faker"), "error body must not carry x_faker. Was: $text")
    }

    @Test
    fun `error directive without status defaults to 400`() = testRoutes { client ->
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(body("oops [[faker:type=error]]"))
        }
        assertEquals(400, response.status.value)
    }

    @Test
    fun `normal synthetic streams clean OpenAI SSE with no echo`() = testRoutes { client ->
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(body("stream please [[faker:type=normal;ttft=0;itl=1;total=3]]", stream = true))
        }
        assertEquals(200, response.status.value)
        val text = response.bodyAsText()
        assertTrue(text.contains("data: [DONE]"), "stream must terminate with [DONE]. Was:\n$text")
        assertTrue(text.contains("\"finish_reason\":\"stop\""), "stream must carry finish_reason stop. Was:\n$text")
        assertTrue(text.contains("\"usage\":"), "stream must carry the final usage frame. Was:\n$text")
        assertFalse(text.contains("request_id"), "stream must not echo request_id. Was:\n$text")
        assertFalse(text.contains("x_faker"), "stream must not carry x_faker. Was:\n$text")
    }

    @Test
    fun `normal synthetic non-streaming returns clean OpenAI json with no echo`() = testRoutes { client ->
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(body("no stream [[faker:type=normal;ttft=0;itl=1;total=3]]", stream = false))
        }
        assertEquals(200, response.status.value)
        val text = response.bodyAsText()
        assertTrue(text.contains("\"object\":\"chat.completion\""), "must be a non-streaming completion. Was: $text")
        assertTrue(text.contains("\"choices\":"), "must carry choices. Was: $text")
        assertFalse(text.contains("request_id"), "root must not echo request_id. Was: $text")
        assertFalse(text.contains("x_faker"), "root must not carry x_faker. Was: $text")
    }

    @Test
    fun `unknown model is rejected with 404 model_not_found`() = testRoutes { client ->
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            // Valid directive, but the wrong model — model validation must win and 404 first.
            setBody("""{"model":"gpt-4o-fake","messages":[{"role":"user","content":"hi [[faker:type=error;status=429]]"}]}""")
        }
        assertEquals(404, response.status.value, "unknown model must be 404, not the directive's 429")
        val text = response.bodyAsText()
        assertTrue(text.contains("\"code\":\"model_not_found\""), "must carry model_not_found code. Was: $text")
        assertTrue(text.contains("\"type\":\"invalid_request_error\""), "must carry invalid_request_error type. Was: $text")
    }

    @Test
    fun `timeout directive holds the connection open past the client deadline`() = testRoutes { client ->
        // The handler suspends on delay(Long.MAX_VALUE); a bounded wait must NOT see a response.
        val response = withTimeoutOrNull(500) {
            client.post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(body("hang [[faker:type=timeout]]", stream = true))
            }
        }
        assertNull(response, "timeout directive must not produce a response within the deadline")
    }
}
