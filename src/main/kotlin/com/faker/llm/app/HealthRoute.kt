package com.faker.llm.app

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /healthz` — minimal liveness probe.
 *
 * Returns `200 ok` as plain text. Used by k8s liveness/readiness, by k6/gatling
 * pre-checks before load tests, and by curl smoke checks (Task 10).
 */
fun Route.healthRoute() {
    get("/healthz") {
        call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
    }
}
