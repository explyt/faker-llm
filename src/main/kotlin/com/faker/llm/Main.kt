package com.faker.llm

import com.faker.llm.adapter.anthropic.anthropicRoutes
import com.faker.llm.adapter.openai.openAiRoutes
import com.faker.llm.app.FAKER_MODEL_ID
import com.faker.llm.app.healthRoute
import com.faker.llm.app.installFakerErrorHandling
import com.faker.llm.app.modelsRoute
import com.faker.llm.engine.DefaultStreamingEngine
import com.faker.llm.engine.StreamingEngine
import com.faker.llm.pool.PoolLoader
import com.faker.llm.pool.PoolSelector
import com.faker.llm.routing.CompositeRequestRouter
import com.faker.llm.routing.RequestRouter
import com.faker.llm.routing.policies.HeaderDirectivePolicy
import com.faker.llm.routing.policies.PromptDirectivePolicy
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Faker LLM entrypoint. Standard Ktor pattern:
 *  - `main(args)` delegates to [EngineMain.main] so configuration comes from `application.conf`
 *  - the actual wiring lives in [Application.module], referenced from the config
 *
 * Note on native epoll transport: `build.gradle.kts` подтягивает
 * `netty-transport-native-epoll` для Linux (x86_64 + aarch64), но Ktor 3.5 `EngineMain`
 * **сам по себе НЕ подхватывает** epoll — для этого нужен custom `embeddedServer` с
 * `configureBootstrap { group(EpollEventLoopGroup()); channel(EpollServerSocketChannel::class.java) }`.
 * Это нетривиальный рефакторинг (теряем `application.conf`-конфиг для
 * `responseWriteTimeoutSeconds = 120`, который критичен для длинных SSE-стримов),
 * поэтому пока запускаемся через NIO. Если после host-tuning'а понадобится ещё perf —
 * отдельная задача с миграцией Main.kt на `embeddedServer`.
 */
fun main(args: Array<String>) = EngineMain.main(args)

/**
 * Composition root. No DI framework on purpose — locals + constructor injection are enough
 * at this scale and have zero per-request overhead.
 */
private val moduleLogger = LoggerFactory.getLogger("com.faker.llm.module")

@Suppress("unused") // referenced from application.conf
fun Application.module() {
    // FAKER_POOL_DIR env-override is used by the load test (Task 11) to swap in a clean
    // pool without rebuilding. Empty / unset → the default "pool" classpath directory.
    val poolDir = System.getenv("FAKER_POOL_DIR")?.takeIf { it.isNotBlank() }
        ?: PoolLoader.DEFAULT_DIRECTORY
    val poolEntries = PoolLoader().load(poolDir)
    val poolSelector = PoolSelector(poolEntries)
    // FAKER_REQUEST_ID_HEADER lets ops override the header name we read AND echo (per
    // faker-contract.md). Default "X-Request-Id" matches the contract default.
    val requestIdHeader = System.getenv("FAKER_REQUEST_ID_HEADER")?.takeIf { it.isNotBlank() }
        ?: "X-Request-Id"
    // This faker serves exactly one model id; ops can override it without a rebuild. The same id
    // gates /v1/chat/completions (404 for anything else) and is advertised by /v1/models.
    val modelId = System.getenv("FAKER_MODEL_ID")?.takeIf { it.isNotBlank() } ?: FAKER_MODEL_ID
    // Policy order matters (first non-null decision wins):
    //  1. HeaderDirectivePolicy — legacy X-Faker-Directive header (Anthropic surface).
    //  2. PromptDirectivePolicy — the in-band [[faker:...]] marker in the message text. This is the
    //     ONLY directive channel for OpenAI: the license tract strips the body & headers, so the
    //     OpenAI adapter never sets a header directive and this policy always handles it.
    val router: RequestRouter = CompositeRequestRouter(
        listOf(HeaderDirectivePolicy(), PromptDirectivePolicy()),
    )
    val streamingEngine: StreamingEngine = DefaultStreamingEngine()

    // Adapter-agnostic Json for the few responses that go through Ktor plugins
    // (StatusPages, ContentNegotiation). Adapters keep their own Json instances and
    // serialize manually — see Tasks 07/08.
    val ktorJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    install(ContentNegotiation) { json(ktorJson) }
    install(CallLogging) {
        level = Level.INFO
        // No body logging on purpose: at 1000 RPS streaming this would flood the disk
        // and starve GC. method/path/status/duration only.
        //
        // Disable Ktor's ANSI colorization of status/method tokens. Console reads stay
        // colored via logback's %highlight() (which colors only the level token); the
        // FILE appender needs raw text — and XML 1.0 forbids U+001B character refs,
        // so we can't strip ANSI inside the logback pattern. Kill ANSI at source.
        disableDefaultColors()
    }
    install(StatusPages) {
        installFakerErrorHandling(ktorJson, requestIdHeader)
    }

    routing {
        healthRoute()
        // Static model discovery: advertises the configured model (GET /v1/models and /models).
        modelsRoute(modelId)
        // OpenAI: one-directional in-band contract — directive in the message text, clean response.
        // Validates the model: only `modelId` is served, anything else → 404 model_not_found.
        openAiRoutes(poolSelector, router, streamingEngine, modelId)
        // Anthropic still uses the legacy header transport pending its own migration.
        anthropicRoutes(poolSelector, router, streamingEngine, requestIdHeader)
    }

    moduleLogger.info("Faker LLM starting: {} pool entries loaded from '{}'", poolEntries.size, poolDir)
}
