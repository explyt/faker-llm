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
        for ((index, delta) in text.chunkByRange(chunkRange, random).withIndex()) {
            if (index > 0) delay(timing.interChunkMs.randomIn(random))
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
        check(ctx.toolNames.isNotEmpty()) {
            "ToolCall part requires ctx.toolNames to be non-empty — selector should have filtered this entry"
        }
        val toolName = ctx.toolNames.random(random)
        val callId = CallIdGenerator.next(random)
        emit(AbstractStreamEvent.ToolCallStart(toolName, callId))

        // JsonObject.toString() is compact JSON by kotlinx.serialization contract — no PoolJson coupling.
        val argsJson = part.argsTemplate.toString()
        val chunkRange = timing.chunkSizeChars.let { it.min..it.max }
        for ((index, delta) in argsJson.chunkByRange(chunkRange, random).withIndex()) {
            // Pace args chunks even from chunk #0 — they follow ToolCallStart, not a fresh stream head.
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
