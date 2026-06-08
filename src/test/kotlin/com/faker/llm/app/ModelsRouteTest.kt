package com.faker.llm.app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelsRouteTest {

    @Test
    fun `models endpoint always advertises the faker model on both paths`() = testApplication {
        application { routing { modelsRoute() } }

        for (path in listOf("/v1/models", "/models")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, "path=$path")
            val body = response.bodyAsText()
            assertTrue(body.contains("\"object\":\"list\""), "path=$path must be a list. body=$body")
            assertTrue(body.contains("\"id\":\"faker\""), "path=$path must advertise the model id. body=$body")
            assertTrue(body.contains("\"object\":\"model\""), "path=$path entry must be a model object. body=$body")
        }
    }
}
