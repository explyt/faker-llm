package com.faker.llm.app

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The single model id this faker always advertises. */
const val FAKER_MODEL_ID = "faker"

/** Fixed `created` epoch — the listing is static, so a stable value keeps the response deterministic. */
private const val MODEL_CREATED_EPOCH = 1_700_000_000L

@Serializable
private data class ModelList(
    val `object`: String = "list",
    val data: List<ModelObject>,
)

@Serializable
private data class ModelObject(
    val id: String,
    val `object`: String = "model",
    val created: Long = MODEL_CREATED_EPOCH,
    val owned_by: String = "veai",
)

// encodeDefaults = true so the literal `object`/`created`/`owned_by` fields appear on the wire.
private val modelsJson = Json { encodeDefaults = true }

/**
 * `GET /v1/models` (OpenAI standard) and the bare `/models` alias — a static, OpenAI-shaped
 * model listing that ALWAYS advertises exactly one model, [FAKER_MODEL_ID].
 *
 * Clients probe this for stand discovery; a fixed answer keeps it predictable under load. The
 * body never changes, so it is serialized once at registration time — zero per-request cost.
 */
fun Route.modelsRoute(modelId: String = FAKER_MODEL_ID) {
    val body = modelsJson.encodeToString(
        ModelList.serializer(),
        ModelList(data = listOf(ModelObject(id = modelId))),
    )
    get("/v1/models") { call.respondText(body, ContentType.Application.Json) }
    get("/models") { call.respondText(body, ContentType.Application.Json) }
}
