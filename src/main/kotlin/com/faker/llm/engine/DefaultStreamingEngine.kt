package com.faker.llm.engine

import com.faker.llm.domain.AbstractStreamEvent
import com.faker.llm.domain.ErrorBody
import com.faker.llm.domain.HttpErrorEntry
import com.faker.llm.domain.MidStreamError
import com.faker.llm.domain.PoolEntry
import com.faker.llm.domain.RequestContext
import com.faker.llm.domain.ResponsePart
import com.faker.llm.domain.SuccessEntry
import com.faker.llm.domain.TimingProfile
import com.faker.llm.domain.UsageStub
import com.faker.llm.domain.randomIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * Reference [StreamingEngine] implementation: cold `flow {}` with suspending [delay] between
 * emits, so 10k+ concurrent streams scale on coroutines without dedicated threads.
 *
 * Stateless except for the injected [random]. Cancellation works "for free": `delay`/`emit`
 * are cancellable, no `Thread.sleep`/`runBlocking` anywhere.
 */
class DefaultStreamingEngine(
    private val random: Random = Random.Default,
) : StreamingEngine {

    override fun execute(entry: PoolEntry, ctx: RequestContext): Flow<AbstractStreamEvent> {
        require(entry !is HttpErrorEntry) {
            "HttpErrorEntry must be handled by the adapter, not streamed (entry id='${entry.id}')"
        }
        val success = entry as SuccessEntry
        return flow { runSuccess(success, ctx) }
    }

    private suspend fun FlowCollector<AbstractStreamEvent>.runSuccess(
        entry: SuccessEntry,
        ctx: RequestContext,
    ) {
        emit(AbstractStreamEvent.StreamStart)
        delay(entry.timing.ttftMs.randomIn(random))

        val mid = entry.midStreamError
        // Edge case: afterChunks == 0 means inject BEFORE the first content chunk.
        if (mid != null && mid.afterChunks <= 0) {
            emit(streamError(mid))
            return
        }

        var emittedChunks = 0
        var completionChars = 0

        for (part in entry.parts) {
            // Returns true when a mid-stream error fired inside the part — abort the run.
            val aborted = when (part) {
                is ResponsePart.Text -> emitTextLike(
                    text = part.content,
                    timing = entry.timing,
                    wrap = AbstractStreamEvent::TextChunk,
                    mid = mid,
                    onChunkEmitted = { delta ->
                        emittedChunks++
                        completionChars += delta.length
                    },
                    chunksSoFar = { emittedChunks },
                )

                is ResponsePart.Thinking -> emitTextLike(
                    text = part.content,
                    timing = entry.timing,
                    wrap = AbstractStreamEvent::ThinkingChunk,
                    mid = mid,
                    onChunkEmitted = { delta ->
                        emittedChunks++
                        completionChars += delta.length
                    },
                    chunksSoFar = { emittedChunks },
                )

                is ResponsePart.ToolCall -> emitToolCall(
                    part = part,
                    ctx = ctx,
                    timing = entry.timing,
                    mid = mid,
                    onChunkEmitted = { delta ->
                        emittedChunks++
                        completionChars += delta.length
                    },
                    chunksSoFar = { emittedChunks },
                )
            }
            if (aborted) return
        }

        emit(
            AbstractStreamEvent.StreamEnd(
                finishReason = entry.finishReason,
                usage = UsageStub(
                    promptChars = ctx.inspectableContent?.length ?: 0,
                    completionChars = completionChars,
                ),
            ),
        )
    }

    /**
     * Chunks [text], emits each as `wrap(delta)` with inter-chunk delay, accounting for
     * mid-stream error injection after each emit. Returns `true` if the run must abort.
     *
     * The inter-chunk delay is gated on the GLOBAL chunk count ([chunksSoFar]), not a per-part
     * index: this keeps one `itl` pause between every pair of consecutive tokens even across the
     * reasoning→content boundary of a multi-part entry. Without it a `thinking` stream would drop
     * exactly one `itl` at the boundary and run short of the contract's duration model (§4). Only
     * the very first token of the whole stream is unpaced (its lead is the TTFT, already applied).
     */
    private suspend fun FlowCollector<AbstractStreamEvent>.emitTextLike(
        text: String,
        timing: TimingProfile,
        wrap: (String) -> AbstractStreamEvent,
        mid: MidStreamError?,
        onChunkEmitted: (String) -> Unit,
        chunksSoFar: () -> Int,
    ): Boolean {
        val chunkRange = timing.chunkSizeChars.let { it.min..it.max }
        for (delta in text.chunkByRange(chunkRange, random)) {
            if (chunksSoFar() > 0) delay(timing.interChunkMs.randomIn(random))
            emit(wrap(delta))
            onChunkEmitted(delta)
            if (mid != null && chunksSoFar() >= mid.afterChunks) {
                emit(streamError(mid))
                return true
            }
        }
        return false
    }

    private suspend fun FlowCollector<AbstractStreamEvent>.emitToolCall(
        part: ResponsePart.ToolCall,
        ctx: RequestContext,
        timing: TimingProfile,
        mid: MidStreamError?,
        onChunkEmitted: (String) -> Unit,
        chunksSoFar: () -> Int,
    ): Boolean {
        // An explicit per-part name (replay: echo the recorded tool call) wins; otherwise the
        // name is picked from the request's tools (pool/synthetic), which must be non-empty.
        val toolName = part.toolName ?: run {
            check(ctx.toolNames.isNotEmpty()) {
                "ToolCall part requires ctx.toolNames to be non-empty — selector should have filtered this entry"
            }
            ctx.toolNames.random(random)
        }
        val callId = CallIdGenerator.next(random)
        // ToolCallStart is itself a client-visible token (it carries the function name, so the
        // load client counts it and records its ITL gap). Pace it like any other token: one `itl`
        // before it UNLESS it is the very first token of the whole stream (then its lead is the
        // TTFT). Without this, a text/thinking part followed by a tool call would drop one `itl`
        // at the seam (text-chunk → ToolCallStart back-to-back), running short of §4.
        if (chunksSoFar() > 0) delay(timing.interChunkMs.randomIn(random))
        emit(AbstractStreamEvent.ToolCallStart(toolName, callId))

        // JsonObject.toString() is compact JSON by kotlinx.serialization contract — no PoolJson coupling.
        val argsJson = part.argsTemplate.toString()
        val chunkRange = timing.chunkSizeChars.let { it.min..it.max }
        for (delta in argsJson.chunkByRange(chunkRange, random)) {
            // Pace args chunks even from #0: they follow ToolCallStart, which is NOT counted in
            // chunksSoFar(), so the global gate would miss this gap. The pause here is always the
            // ToolCallStart → first-arg gap (a fresh stream head is impossible — ToolCallStart precedes).
            delay(timing.interChunkMs.randomIn(random))
            emit(AbstractStreamEvent.ToolCallArgsChunk(delta))
            onChunkEmitted(delta)
            if (mid != null && chunksSoFar() >= mid.afterChunks) {
                emit(streamError(mid))
                // No ToolCallEnd — the stream is dead after a mid-stream error.
                return true
            }
        }
        emit(AbstractStreamEvent.ToolCallEnd(callId))
        return false
    }

    private fun streamError(mid: MidStreamError): AbstractStreamEvent.StreamError =
        AbstractStreamEvent.StreamError(
            kind = mid.kind,
            body = ErrorBody(
                type = "stream_error",
                message = "Injected mid-stream error: ${mid.kind}",
            ),
        )
}
