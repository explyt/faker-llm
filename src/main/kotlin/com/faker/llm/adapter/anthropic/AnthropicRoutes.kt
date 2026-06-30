package com.faker.llm.adapter.anthropic

import com.faker.llm.adapter.anthropic.dto.MessagesRequest
import com.faker.llm.adapter.anthropic.dto.MessagesResponse
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
 * Registers `POST /v1/messages`. Self-contained: uses [receiveText]/[respondText]/
 * [respondTextWriter] directly instead of relying on a globally installed
 * `ContentNegotiation` plugin (same approach as the OpenAI adapter).
 *
 * The faker contract is one-directional and in-band: the directive rides inside the message
 * text (read by PromptDirectivePolicy from `inspectableContent`), and the response is CLEAN
 * Anthropic with no echo — no `request_id`, no `faker_elapsed_ms` (faker-contract.md §7). The
 * client derives gateway overhead from the timing it requested in the directive.
 *
 * The Anthropic `anthropic-version` header is intentionally NOT validated — design choice for the
 * load-test faker.
 */
fun Route.anthropicRoutes(
    selector: PoolSelector,
    router: RequestRouter,
    engine: StreamingEngine,
    mapper: AnthropicResponseMapper = AnthropicResponseMapper(),
) {
    val json = AnthropicJson.json

    post("/v1/messages") {
        val raw = call.receiveText()
        val request = runCatching {
            json.decodeFromString(MessagesRequest.serializer(), raw)
        }.getOrElse { e ->
            respondInvalidRequest(call, mapper, reason = e.message ?: "invalid JSON")
            return@post
        }

        val ctx = AnthropicRequestMapper.toContext(request)
        val decision = router.route(ctx)

        // SyntheticHttpError short-circuits the pool entirely — it's an injected error from the
        // in-band directive (PromptDirectivePolicy), not a randomly picked HttpErrorEntry.
        if (decision is RoutingDecision.SyntheticHttpError) {
            respondSyntheticError(call, mapper, decision)
            return@post
        }

        if (decision is RoutingDecision.SyntheticBehavior) {
            handleSynthetic(call, mapper, engine, decision, ctx, request.model)
            return@post
        }

        val entry = selector.pick(ctx, decision)
        when (entry) {
            is HttpErrorEntry -> respondHttpError(call, mapper, entry)
            is SuccessEntry -> {
                if (ctx.stream) {
                    streamSuccess(call, mapper, engine, entry, ctx, request.model)
                } else {
                    respondNonStreaming(call, mapper, engine, entry, ctx, request.model)
                }
            }
        }
    }
}

private suspend fun respondSyntheticError(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    decision: RoutingDecision.SyntheticHttpError,
) {
    respondJson(
        call,
        HttpStatusCode.fromValue(decision.status),
        mapper.buildErrorEnvelope(
            type = anthropicErrorTypeFor(decision.status),
            message = defaultErrorMessageFor(decision.status),
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
    reason: String,
) {
    respondJson(
        call,
        HttpStatusCode.BadRequest,
        mapper.buildErrorEnvelope(
            type = "invalid_request_error",
            message = "Invalid request: $reason",
        ),
    )
}

private suspend fun respondHttpError(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    entry: HttpErrorEntry,
) {
    delay(entry.preResponseDelayMs.randomIn())
    respondJson(
        call,
        HttpStatusCode.fromValue(entry.status),
        mapper.buildErrorEnvelope(
            type = entry.errorBody.type,
            message = entry.errorBody.message,
        ),
    )
}

private suspend fun respondNonStreaming(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    engine: StreamingEngine,
    entry: SuccessEntry,
    ctx: RequestContext,
    model: String,
) {
    val response = mapper.buildNonStreaming(
        events = engine.execute(entry, ctx),
        ctx = ctx,
        model = model,
    )
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
    decision: RoutingDecision.SyntheticBehavior,
    ctx: RequestContext,
    model: String,
) {
    if (decision.directive.type == "timeout") {
        // Suspend until client disconnect. The client counts the fired timeout as success.
        delay(Long.MAX_VALUE)
        return
    }
    // A replay directive with a corrupted/undecodable payload throws IllegalArgumentException;
    // answer 400 (transport mangled the marker), not a generic 500 — parity with OpenAiRoutes.
    val entry = try {
        SyntheticEntryBuilder.buildEntry(decision.directive)
    } catch (e: IllegalArgumentException) {
        respondInvalidRequest(call, mapper, reason = e.message ?: "invalid replay payload")
        return
    }
    val effectiveCtx = SyntheticEntryBuilder.overrideContext(ctx, decision.directive)
    if (effectiveCtx.stream) {
        streamSuccess(call, mapper, engine, entry, effectiveCtx, model)
    } else {
        respondNonStreaming(call, mapper, engine, entry, effectiveCtx, model)
    }
}

private suspend fun streamSuccess(
    call: ApplicationCall,
    mapper: AnthropicResponseMapper,
    engine: StreamingEngine,
    entry: SuccessEntry,
    ctx: RequestContext,
    model: String,
) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.response.headers.append(HttpHeaders.Connection, "keep-alive")
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        mapper.streamSse(engine.execute(entry, ctx), ctx, model, this)
    }
}

private suspend fun respondJson(call: ApplicationCall, status: HttpStatusCode, body: String) {
    call.respondText(text = body, contentType = ContentType.Application.Json, status = status)
}
