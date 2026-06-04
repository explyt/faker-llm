package com.faker.llm.adapter.anthropic

import com.faker.llm.adapter.anthropic.dto.MessagesRequest
import com.faker.llm.adapter.anthropic.dto.MessagesResponse
import com.faker.llm.app.APPLIED_TIMING_HEADER
import com.faker.llm.app.AppliedTiming
import com.faker.llm.app.elapsedMsSince
import com.faker.llm.app.estimateForStreaming
import com.faker.llm.app.fromElapsed
import com.faker.llm.app.markRequestStart
import com.faker.llm.app.requestStartedNanos
import com.faker.llm.app.toHeaderValue
import com.faker.llm.domain.HttpErrorEntry
import com.faker.llm.domain.RequestContext
import com.faker.llm.domain.SuccessEntry
import com.faker.llm.domain.randomIn
import com.faker.llm.engine.StreamingEngine
import com.faker.llm.engine.SyntheticEntryBuilder
import com.faker.llm.pool.PoolSelector
import com.faker.llm.routing.RequestRouter
import com.faker.llm.routing.RoutingDecision
import com.faker.llm.routing.policies.HeaderDirectivePolicy
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * Registers `POST /v1/messages`. Self-contained: uses [receiveText]/[respondText]/
 * [respondTextWriter] directly instead of relying on a globally installed
 * `ContentNegotiation` plugin (same approach as the OpenAI adapter).
 *
 * The very first instruction stamps `System.nanoTime()` via [markRequestStart] so every
 * downstream emitter can compute the `faker_elapsed_ms` instrumentation field against a
 * single anchor (see `app/RequestTimer.kt`).
 *
 * The Anthropic `anthropic-version` header is intentionally NOT validated — design choice
 * for the load-test faker (per PLAN.md caveats).
 */
fun Route.anthropicRoutes(
    selector: PoolSelector,
    router: RequestRouter,
    engine: StreamingEngine,
    requestIdHeader: String = "X-Request-Id",
    mapper: AnthropicResponseMapper = AnthropicResponseMapper(),
) {
    val json = AnthropicJson.json

    post("/v1/messages") {
        call.markRequestStart()
        val requestId = call.request.headers[requestIdHeader]
        echoRequestId(call, requestIdHeader, requestId)
        val directiveHeader = call.request.headers[HeaderDirectivePolicy.HEADER_NAME]
        val raw = call.receiveText()
        val request = runCatching {
            json.decodeFromString(MessagesRequest.serializer(), raw)
        }.getOrElse { e ->
            respondInvalidRequest(call, mapper, json, requestId, e.message ?: "invalid JSON")
            return@post
        }

        val ctx = AnthropicRequestMapper.toContext(request, directiveHeader)
        val decision = router.route(ctx)

        // SyntheticHttpError short-circuits the pool entirely — it's an injected error
        // from the client (X-Faker-Directive), not a randomly picked HttpErrorEntry.
        if (decision is RoutingDecision.SyntheticHttpError) {
            respondSyntheticError(call, mapper, json, requestId, decision)
            return@post
        }

        if (decision is RoutingDecision.SyntheticBehavior) {
            handleSynthetic(call, mapper, engine, json, requestId, decision, ctx, request.model)
            return@post
        }

        val entry = selector.pick(ctx, decision)
        when (entry) {
            is HttpErrorEntry -> respondHttpError(call, mapper, json, requestId, entry)
            is SuccessEntry -> {
                if (ctx.stream) {
                    streamSuccess(call, mapper, engine, json, entry, ctx, request.model)
                } else {
                    respondNonStreaming(call, mapper, engine, json, entry, ctx, request.model)
                }
            }
        }
    }
}

private fun echoRequestId(call: ApplicationCall, headerName: String, requestId: String?) {
    if (requestId != null) call.response.headers.append(headerName, requestId)
}

private fun appendAppliedTiming(call: ApplicationCall, json: Json, applied: AppliedTiming) {
    call.response.headers.append(APPLIED_TIMING_HEADER, applied.toHeaderValue(json))
}

private suspend fun respondSyntheticError(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    json: Json,
    requestId: String?,
    decision: RoutingDecision.SyntheticHttpError,
) {
    // faker-contract 2.md §8: errors carry an applied-timing of 0/0/0 — the stub answers
    // synthetically with no upstream work, so the client attributes the entire E2E to
    // gateway/network overhead.
    appendAppliedTiming(call, json, fromElapsed(0L, 0L))
    respondJson(
        call,
        HttpStatusCode.fromValue(decision.status),
        mapper.buildErrorEnvelope(
            type = anthropicErrorTypeFor(decision.status),
            message = defaultErrorMessageFor(decision.status),
            requestStartNanos = call.requestStartedNanos(),
            requestId = requestId,
        ),
    )
}

/** Anthropic categorical error types per their public API docs. */
private fun anthropicErrorTypeFor(status: Int): String = when (status) {
    400 -> "invalid_request_error"
    401 -> "authentication_error"
    403 -> "permission_error"
    404 -> "not_found_error"
    413 -> "request_too_large"
    429 -> "rate_limit_error"
    529 -> "overloaded_error"
    in 400..499 -> "invalid_request_error"
    else -> "api_error"
}

/** Faker-chosen `error.message` text — clients only check the HTTP status. */
private fun defaultErrorMessageFor(status: Int): String = when {
    status == 429 -> "Rate limit exceeded"
    status in 400..499 -> "Faker injected client error"
    else -> "Faker injected server error"
}

private suspend fun respondInvalidRequest(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    json: Json,
    requestId: String?,
    reason: String,
) {
    // Real request decoding failure — error path, applied-timing stays 0/0/0.
    appendAppliedTiming(call, json, fromElapsed(0L, 0L))
    respondJson(
        call,
        HttpStatusCode.BadRequest,
        mapper.buildErrorEnvelope(
            type = "invalid_request_error",
            message = "Invalid request: $reason",
            requestStartNanos = call.requestStartedNanos(),
            requestId = requestId,
        ),
    )
}

private suspend fun respondHttpError(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    json: Json,
    requestId: String?,
    entry: HttpErrorEntry,
) {
    delay(entry.preResponseDelayMs.randomIn())
    appendAppliedTiming(call, json, fromElapsed(0L, 0L))
    respondJson(
        call,
        HttpStatusCode.fromValue(entry.status),
        mapper.buildErrorEnvelope(
            type = entry.errorBody.type,
            message = entry.errorBody.message,
            requestStartNanos = call.requestStartedNanos(),
            requestId = requestId,
        ),
    )
}

private suspend fun respondNonStreaming(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    engine: StreamingEngine,
    json: Json,
    entry: SuccessEntry,
    ctx: RequestContext,
    model: String,
) {
    val response = mapper.buildNonStreaming(
        events = engine.execute(entry, ctx),
        ctx = ctx,
        model = model,
        requestStartNanos = call.requestStartedNanos(),
    )
    val elapsedMs = elapsedMsSince(call.requestStartedNanos())
    appendAppliedTiming(call, json, AppliedTiming(ttft_ms = entry.timing.ttftMs.max, itl_ms = 0L, total_ms = elapsedMs))
    respondJson(
        call,
        HttpStatusCode.OK,
        AnthropicJson.json.encodeToString(MessagesResponse.serializer(), response),
    )
}

private suspend fun handleSynthetic(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    engine: StreamingEngine,
    json: Json,
    @Suppress("UNUSED_PARAMETER") requestId: String?,
    decision: RoutingDecision.SyntheticBehavior,
    ctx: RequestContext,
    model: String,
) {
    if (decision.directive.type == "timeout") {
        // Suspend until client disconnect. No X-Faker-Applied-Timing per contract.
        delay(Long.MAX_VALUE)
        return
    }
    val entry = SyntheticEntryBuilder.buildEntry(decision.directive)
    val effectiveCtx = SyntheticEntryBuilder.overrideContext(ctx, decision.directive)
    if (effectiveCtx.stream) {
        streamSuccess(call, mapper, engine, json, entry, effectiveCtx, model)
    } else {
        respondNonStreaming(call, mapper, engine, json, entry, effectiveCtx, model)
    }
}

private suspend fun streamSuccess(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    engine: StreamingEngine,
    json: Json,
    entry: SuccessEntry,
    ctx: RequestContext,
    model: String,
) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.response.headers.append(HttpHeaders.Connection, "keep-alive")
    // Streaming compromise: total_ms is an ESTIMATE — we have to commit to a value before
    // sending the first byte, so we project from the SuccessEntry's timing profile.
    appendAppliedTiming(call, json, estimateForStreaming(entry))
    val startNanos = call.requestStartedNanos()
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        mapper.streamSse(engine.execute(entry, ctx), ctx, model, this, startNanos)
    }
}

private suspend fun respondJson(call: ApplicationCall, status: HttpStatusCode, body: String) {
    call.respondText(text = body, contentType = ContentType.Application.Json, status = status)
}
