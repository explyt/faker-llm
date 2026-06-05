package com.faker.llm.adapter.openai

import com.faker.llm.adapter.openai.dto.ChatCompletionRequest
import com.faker.llm.adapter.openai.dto.ChatCompletionResponse
import com.faker.llm.app.markRequestStart
import com.faker.llm.app.rememberRequestId
import com.faker.llm.app.requestStartedNanos
import com.faker.llm.domain.HttpErrorEntry
import com.faker.llm.domain.RequestContext
import com.faker.llm.domain.SuccessEntry
import com.faker.llm.domain.randomIn
import com.faker.llm.engine.StreamingEngine
import com.faker.llm.engine.SyntheticEntryBuilder
import com.faker.llm.pool.PoolSelector
import com.faker.llm.routing.RequestRouter
import com.faker.llm.routing.RoutingDecision
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

/**
 * Registers `POST /v1/chat/completions`. Wires the OpenAI surface to the provider-agnostic
 * core: parse → route → select → branch (HTTP error / streaming / non-streaming).
 *
 * The very first instruction stamps `System.nanoTime()` via [markRequestStart] so the mapper
 * can compute the MEASURED `x_faker.applied_timing.total_ms` against a single anchor.
 *
 * Faker contract transport is body-only (the license tract strips headers): the directive and
 * `request_id` are read from the request body, and the same `request_id` plus the applied-timing
 * echo are written back into the response body — never headers.
 */
fun Route.openAiRoutes(
    selector: PoolSelector,
    router: RequestRouter,
    engine: StreamingEngine,
    mapper: OpenAiResponseMapper = OpenAiResponseMapper(),
) {
    val json = OpenAiJson.json

    post("/v1/chat/completions") {
        call.markRequestStart()
        val raw = call.receiveText()
        val request = runCatching {
            json.decodeFromString(ChatCompletionRequest.serializer(), raw)
        }.getOrElse { e ->
            // Body did not parse → no request_id to echo (contract-acceptable).
            respondInvalidRequest(call, mapper, requestId = null, reason = e.message ?: "invalid JSON")
            return@post
        }

        // request_id rides in the body now; stash it so error handlers thrown later can echo it.
        val requestId = request.request_id
        call.rememberRequestId(requestId)

        val ctx = OpenAiRequestMapper.toContext(request)
        val decision = router.route(ctx)

        // SyntheticHttpError short-circuits the pool entirely — it's an injected error from the
        // client directive (x_faker.directive), not a randomly picked HttpErrorEntry.
        if (decision is RoutingDecision.SyntheticHttpError) {
            respondSyntheticError(call, mapper, requestId, decision)
            return@post
        }

        if (decision is RoutingDecision.SyntheticBehavior) {
            handleSynthetic(call, mapper, engine, requestId, decision, ctx, request.model)
            return@post
        }

        val entry = selector.pick(ctx, decision)
        when (entry) {
            is HttpErrorEntry -> respondHttpError(call, mapper, requestId, entry)
            is SuccessEntry -> {
                if (ctx.stream) {
                    streamSuccess(call, mapper, engine, entry, ctx, request.model, requestId)
                } else {
                    respondNonStreaming(call, mapper, engine, entry, ctx, request.model, requestId)
                }
            }
        }
    }
}

/** Planned TTFT for the applied-timing echo: midpoint of the entry's ttft range. */
private fun plannedTtftMs(entry: SuccessEntry): Long =
    (entry.timing.ttftMs.min + entry.timing.ttftMs.max) / 2L

/** Planned inter-token latency for the applied-timing echo: midpoint of the entry's itl range. */
private fun plannedItlMs(entry: SuccessEntry): Long =
    (entry.timing.interChunkMs.min + entry.timing.interChunkMs.max) / 2L

private suspend fun respondSyntheticError(
    call: ApplicationCall,
    mapper: OpenAiResponseMapper,
    requestId: String?,
    decision: RoutingDecision.SyntheticHttpError,
) {
    respondJson(
        call,
        HttpStatusCode.fromValue(decision.status),
        mapper.buildErrorEnvelope(
            message = defaultErrorMessageFor(decision.status),
            type = openAiErrorTypeFor(decision.status),
            code = defaultErrorCodeFor(decision.status),
            requestId = requestId,
        ),
    )
}

/** OpenAI uses categorical `error.type` values; map HTTP status to the closest one. */
private fun openAiErrorTypeFor(status: Int): String = when {
    status == 429 -> "rate_limit_error"
    status in 400..499 -> "invalid_request_error"
    else -> "server_error"
}

/** Faker-chosen `error.code` text — the contract leaves the choice to the server. */
private fun defaultErrorCodeFor(status: Int): String? = when {
    status == 429 -> "rate_limit_exceeded"
    else -> null
}

/** Faker-chosen `error.message` text — clients are only required to check the HTTP status. */
private fun defaultErrorMessageFor(status: Int): String = when {
    status == 429 -> "Rate limit exceeded"
    status in 400..499 -> "Faker injected client error"
    else -> "Faker injected server error"
}

private suspend fun respondInvalidRequest(
    call: ApplicationCall,
    mapper: OpenAiResponseMapper,
    requestId: String?,
    reason: String,
) {
    respondJson(
        call,
        HttpStatusCode.BadRequest,
        mapper.buildErrorEnvelope(
            message = "Invalid request: $reason",
            type = "invalid_request_error",
            requestId = requestId,
        ),
    )
}

private suspend fun respondHttpError(
    call: ApplicationCall,
    mapper: OpenAiResponseMapper,
    requestId: String?,
    entry: HttpErrorEntry,
) {
    delay(entry.preResponseDelayMs.randomIn())
    respondJson(
        call,
        HttpStatusCode.fromValue(entry.status),
        mapper.buildErrorEnvelope(
            message = entry.errorBody.message,
            type = entry.errorBody.type,
            requestId = requestId,
        ),
    )
}

private suspend fun respondNonStreaming(
    call: ApplicationCall,
    mapper: OpenAiResponseMapper,
    engine: StreamingEngine,
    entry: SuccessEntry,
    ctx: RequestContext,
    model: String,
    requestId: String?,
) {
    val response = mapper.buildNonStreaming(
        events = engine.execute(entry, ctx),
        model = model,
        requestStartNanos = call.requestStartedNanos(),
        requestId = requestId,
        plannedTtftMs = plannedTtftMs(entry),
        plannedItlMs = plannedItlMs(entry),
    )
    respondJson(
        call,
        HttpStatusCode.OK,
        OpenAiJson.json.encodeToString(ChatCompletionResponse.serializer(), response),
    )
}

private suspend fun handleSynthetic(
    call: ApplicationCall,
    mapper: OpenAiResponseMapper,
    engine: StreamingEngine,
    requestId: String?,
    decision: RoutingDecision.SyntheticBehavior,
    ctx: RequestContext,
    model: String,
) {
    if (decision.directive.type == "timeout") {
        // Suspend until client disconnect. Coroutine cancellation tears this down cleanly;
        // no applied-timing echo per contract §8 (timeout produces no response body).
        delay(Long.MAX_VALUE)
        return
    }
    val entry = SyntheticEntryBuilder.buildEntry(decision.directive)
    val effectiveCtx = SyntheticEntryBuilder.overrideContext(ctx, decision.directive)
    if (effectiveCtx.stream) {
        streamSuccess(call, mapper, engine, entry, effectiveCtx, model, requestId)
    } else {
        respondNonStreaming(call, mapper, engine, entry, effectiveCtx, model, requestId)
    }
}

private suspend fun respondJson(call: ApplicationCall, status: HttpStatusCode, body: String) {
    call.respondText(text = body, contentType = ContentType.Application.Json, status = status)
}

private suspend fun streamSuccess(
    call: ApplicationCall,
    mapper: OpenAiResponseMapper,
    engine: StreamingEngine,
    entry: SuccessEntry,
    ctx: RequestContext,
    model: String,
    requestId: String?,
) {
    // Must be set BEFORE respondTextWriter starts the response body.
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.response.headers.append(HttpHeaders.Connection, "keep-alive")
    val startNanos = call.requestStartedNanos()
    val plannedTtft = plannedTtftMs(entry)
    val plannedItl = plannedItlMs(entry)
    // No applied-timing header up front anymore: the echo rides the final SSE chunk with a
    // MEASURED total_ms (faker-contract.md §8), so there is nothing to commit before first byte.
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        mapper.streamSse(engine.execute(entry, ctx), model, this, startNanos, requestId, plannedTtft, plannedItl)
    }
}
