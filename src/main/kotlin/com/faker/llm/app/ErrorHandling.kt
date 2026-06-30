package com.faker.llm.app

import com.faker.llm.adapter.anthropic.dto.AnthropicErrorBody
import com.faker.llm.adapter.anthropic.dto.AnthropicErrorEnvelope
import com.faker.llm.adapter.openai.dto.OpenAiErrorBody
import com.faker.llm.adapter.openai.dto.OpenAiErrorEnvelope
import com.faker.llm.pool.EmptyPoolException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val errorHandlingLogger = LoggerFactory.getLogger("com.faker.llm.app.ErrorHandling")

/**
 * Provider-agnostic StatusPages glue: turns thrown exceptions into JSON error bodies that
 * match the calling provider's wire shape. Dispatch is by request path prefix
 * (`/v1/messages` → Anthropic, otherwise → OpenAI).
 *
 * Both providers emit a CLEAN error envelope with no faker echo — the contract is one-directional
 * and the license tract rewrites the error body anyway (faker-contract.md §7); the client checks
 * only the HTTP status class.
 */
internal fun StatusPagesConfig.installFakerErrorHandling(json: Json) {
    exception<EmptyPoolException> { call, cause ->
        respondError(call, json, HttpStatusCode.InternalServerError, "pool_misconfigured", cause.message ?: "No applicable pool entry", cause)
    }
    exception<SerializationException> { call, cause ->
        respondError(call, json, HttpStatusCode.BadRequest, "invalid_request", cause.message ?: "Invalid request payload", cause)
    }
    exception<Throwable> { call, cause ->
        respondError(call, json, HttpStatusCode.InternalServerError, "internal_error", cause.message ?: "Internal error", cause)
    }
}

private suspend fun respondError(
    call: ApplicationCall,
    json: Json,
    status: HttpStatusCode,
    type: String,
    message: String,
    cause: Throwable,
) {
    errorHandlingLogger.error(
        "Faker error handler caught exception: status={} type={} method={} path={} causeType={} causeMessage={}",
        status.value,
        type,
        call.request.local.method.value,
        call.request.path(),
        cause.javaClass.name,
        cause.message,
        cause,
    )
    val body = if (call.request.path().startsWith(ANTHROPIC_PATH_PREFIX)) {
        // Anthropic: clean error envelope, no echo — same one-directional contract as OpenAI.
        json.encodeToString(
            AnthropicErrorEnvelope.serializer(),
            AnthropicErrorEnvelope(error = AnthropicErrorBody(type = type, message = message)),
        )
    } else {
        // OpenAI: clean error envelope, no echo — the client checks only the HTTP class.
        json.encodeToString(
            OpenAiErrorEnvelope.serializer(),
            OpenAiErrorEnvelope(error = OpenAiErrorBody(message = message, type = type)),
        )
    }
    try {
        call.respondText(body, ContentType.Application.Json, status)
    } catch (writeFailure: Throwable) {
        errorHandlingLogger.warn(
            "Cannot write error envelope, response already committed: path={} secondaryType={} secondaryMessage={}",
            call.request.path(),
            writeFailure.javaClass.name,
            writeFailure.message,
        )
    }
}

private const val ANTHROPIC_PATH_PREFIX = "/v1/messages"
