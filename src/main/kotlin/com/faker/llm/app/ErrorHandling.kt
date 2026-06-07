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
 * Provider-specific body shape:
 *  - OpenAI: clean OpenAI error envelope, no faker echo (the contract is one-directional and the
 *    license tract rewrites the error body anyway — faker-contract.md §7).
 *  - Anthropic (legacy): `request_id` + applied-timing still echoed via headers and
 *    `faker_elapsed_ms` in the body, measured from the [markRequestStart] anchor.
 */
internal fun StatusPagesConfig.installFakerErrorHandling(json: Json, requestIdHeader: String) {
    exception<EmptyPoolException> { call, cause ->
        respondError(
            call = call,
            json = json,
            requestIdHeader = requestIdHeader,
            status = HttpStatusCode.InternalServerError,
            type = "pool_misconfigured",
            message = cause.message ?: "No applicable pool entry",
            cause = cause,
        )
    }
    exception<SerializationException> { call, cause ->
        respondError(
            call = call,
            json = json,
            requestIdHeader = requestIdHeader,
            status = HttpStatusCode.BadRequest,
            type = "invalid_request",
            message = cause.message ?: "Invalid request payload",
            cause = cause,
        )
    }
    exception<Throwable> { call, cause ->
        respondError(
            call = call,
            json = json,
            requestIdHeader = requestIdHeader,
            status = HttpStatusCode.InternalServerError,
            type = "internal_error",
            message = cause.message ?: "Internal error",
            cause = cause,
        )
    }
}

private suspend fun respondError(
    call: ApplicationCall,
    json: Json,
    requestIdHeader: String,
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
    // Headers to append before writing the body — populated only on the legacy Anthropic path.
    val legacyHeaders = mutableListOf<Pair<String, String>>()
    val body = if (call.request.path().startsWith(ANTHROPIC_PATH_PREFIX)) {
        // Legacy header transport (Anthropic), pending migration to the body-only contract.
        val elapsedMs = elapsedMsSince(call.requestStartedNanos())
        val requestId = call.request.headers[requestIdHeader]
        if (requestId != null) legacyHeaders += requestIdHeader to requestId
        legacyHeaders += APPLIED_TIMING_HEADER to fromElapsed(0L, elapsedMs).toHeaderValue(json)
        json.encodeToString(
            AnthropicErrorEnvelope.serializer(),
            AnthropicErrorEnvelope(
                error = AnthropicErrorBody(type = type, message = message),
                faker_elapsed_ms = elapsedMs,
                request_id = requestId,
            ),
        )
    } else {
        // Faker contract (OpenAI): clean error envelope, no echo — the contract is one-directional
        // and the license tract rewrites the error body; the client checks only the HTTP class.
        json.encodeToString(
            OpenAiErrorEnvelope.serializer(),
            OpenAiErrorEnvelope(
                error = OpenAiErrorBody(message = message, type = type),
            ),
        )
    }
    try {
        legacyHeaders.forEach { (name, value) -> call.response.headers.append(name, value) }
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
