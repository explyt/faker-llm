package com.faker.llm

import com.faker.llm.adapter.anthropic.anthropicRoutes
import com.faker.llm.adapter.openai.openAiRoutes
import com.faker.llm.app.healthRoute
import com.faker.llm.app.installFakerErrorHandling
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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerSocketChannel
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Faker LLM entrypoint.
 *
 * Раньше тут был `EngineMain.main(args)` + HOCON `application.conf`. Сейчас мы
 * руками поднимаем [embeddedServer], потому что нужно явно подменить Netty
 * transport на native epoll на Linux (под нагрузкой 3k+ RPS). На macOS / Windows
 * `Epoll.isAvailable()` вернёт false, и мы прозрачно фолбэкаемся на NIO —
 * dev-машины не ломаются.
 *
 * `src/main/resources/application.conf` после этого перехода становится мёртвым
 * конфигом — оставлен на месте намеренно, чтобы не ломать чужие ожидания и
 * чтобы было видно, что `responseWriteTimeoutSeconds` теперь выставляется
 * программно ниже (env `FAKER_RESPONSE_WRITE_TIMEOUT_SECONDS` поведение сохранено).
 */
private val moduleLogger = LoggerFactory.getLogger("com.faker.llm.module")
private val bootLogger = LoggerFactory.getLogger("com.faker.llm.boot")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val responseWriteTimeoutSeconds = System.getenv("FAKER_RESPONSE_WRITE_TIMEOUT_SECONDS")
        ?.toIntOrNull()
        ?: 120

    val epollAvailable = Epoll.isAvailable()
    if (epollAvailable) {
        bootLogger.info("[netty] using native epoll transport (Linux)")
    } else {
        // Это нормально на macOS / Windows / non-x86_64-Linux без подходящего classifier'а.
        // На проде (Ubuntu) ожидаем true; если тут всё равно false — что-то не так с classpath.
        bootLogger.warn(
            "[netty] native epoll unavailable, falling back to NIO. Reason: {}",
            Epoll.unavailabilityCause()?.message ?: "n/a",
        )
    }

    val server = embeddedServer(
        Netty,
        port = port,
        host = "0.0.0.0",
        module = Application::module,
    ) {
        this.responseWriteTimeoutSeconds = responseWriteTimeoutSeconds
        // SSE-клиенты могут долго не слать ничего на сервер после initial request —
        // явно ставим 0 (infinite) чтобы не плодить лишних reads timeouts.
        this.requestReadTimeoutSeconds = 0
        // TCP keep-alive чтобы вычищать застрявшие коннекты, которые клиент уже бросил
        // без FIN (стандартная история под нагрузкой). Период — sysctl уровня ОС.
        this.tcpKeepAlive = true

        if (epollAvailable) {
            configureBootstrap = {
                group(EpollEventLoopGroup())
                channel(EpollServerSocketChannel::class.java)
            }
        }
    }

    // Graceful shutdown: docker stop -> SIGTERM -> tini -> JVM shutdown hook -> server.stop().
    // 1s grace для in-flight запросов, 10s полный timeout — дальше Netty форсит close.
    Runtime.getRuntime().addShutdownHook(Thread {
        bootLogger.info("[shutdown] SIGTERM received, stopping server (grace=1s, timeout=10s)")
        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 10_000)
    })

    bootLogger.info("[boot] Faker LLM listening on 0.0.0.0:{} (transport={})", port, if (epollAvailable) "epoll" else "nio")
    server.start(wait = true)
}

/**
 * Composition root. No DI framework on purpose — locals + constructor injection are enough
 * at this scale and have zero per-request overhead.
 */
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
    // HeaderDirectivePolicy is BEFORE PromptDirectivePolicy on purpose: an explicit
    // X-Faker-Directive from the client overrides any inline [[faker:...]] marker in
    // the prompt text (see faker-contract.md).
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
        openAiRoutes(poolSelector, router, streamingEngine, requestIdHeader)
        anthropicRoutes(poolSelector, router, streamingEngine, requestIdHeader)
    }

    moduleLogger.info("Faker LLM starting: {} pool entries loaded from '{}'", poolEntries.size, poolDir)
}
