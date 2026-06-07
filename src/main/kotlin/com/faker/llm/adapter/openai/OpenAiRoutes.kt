package com.faker.llm.adapter.openai

import com.faker.llm.adapter.openai.dto.ChatCompletionRequest
import com.faker.llm.adapter.openai.dto.ChatCompletionResponse
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
 * The faker contract is one-directional and in-band: the directive rides inside the message text
 * (the license tract strips the body and headers), and the response is CLEAN OpenAI with no echo —
 * no `request_id`, no `x_faker.applied_timing` (faker-contract.md §7–§8).
 */
fun Route.openAiRoutes(
    selector: PoolSelector,
    router: RequestRouter,
    engine: StreamingEngine,
    mapper: OpenAiResponseMapper = OpenAiResponseMapper(),
) {
    val json = OpenAiJson.json

    post("/v1/chat/completions") {
        val raw = call.receiveText()
        val request = runCatching {
            json.decodeFromString(ChatCompletionRequest.serializer(), raw)
        }.getOrElse { e ->
            respondInvalidRequest(call, mapper, reason = e.message ?: "invalid JSON")
            return@post
        }

        val ctx = OpenAiRequestMapper.toContext(request)
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
    mapper: OpenAiResponseMapper,
    decision: RoutingDecision.SyntheticHttpError,
) {
    respondJson(
        call,
        HttpStatusCode.fromValue(decision.status),
        mapper.buildErrorEnvelope(
            message = defaultErrorMessageFor(decision.status),
            type = openAiErrorTypeFor(decision.status),
            code = defaultErrorCodeFor(decision.status),
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
    reason: String,
) {
    respondJson(
        call,
        HttpStatusCode.BadRequest,
        mapper.buildErrorEnvelope(
            message = "Invalid request: $reason",
            type = "invalid_request_error",
        ),
    )
}

private suspend fun respondHttpError(
    call: ApplicationCall,
    mapper: OpenAiResponseMapper,
    entry: HttpErrorEntry,
) {
    delay(entry.preResponseDelayMs.randomIn())
    respondJson(
        call,
        HttpStatusCode.fromValue(entry.status),
        mapper.buildErrorEnvelope(
            message = entry.errorBody.message,
            type = entry.errorBody.type,
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
) {
    val response = mapper.buildNonStreaming(
        events = engine.execute(entry, ctx),
        model = model,
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
    decision: RoutingDecision.SyntheticBehavior,
    ctx: RequestContext,
    model: String,
) {
    if (decision.directive.type == "timeout") {
        // Suspend until client disconnect. Coroutine cancellation tears this down cleanly;
        // the client counts the fired timeout as success (faker-contract.md §5/§10).
        delay(Long.MAX_VALUE)
        return
    }
    val entry = SyntheticEntryBuilder.buildEntry(decision.directive)
    val effectiveCtx = SyntheticEntryBuilder.overrideContext(ctx, decision.directive)
    if (effectiveCtx.stream) {
        streamSuccess(call, mapper, engine, entry, effectiveCtx, model)
    } else {
        respondNonStreaming(call, mapper, engine, entry, effectiveCtx, model)
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
) {
    // Must be set BEFORE respondTextWriter starts the response body.
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.response.headers.append(HttpHeaders.Connection, "keep-alive")
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        mapper.streamSse(engine.execute(entry, ctx), model, this)
    }
}
