# syntax=docker/dockerfile:1.7
# ----- build stage --------------------------------------------------
# Гоним shadowJar в build-контейнере с полным JDK + Gradle wrapper.
# Кешируем Gradle deps в отдельном слое: при изменении src/ их не качаем заново.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Сначала только wrapper + gradle-конфиги — этот слой меняется редко
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x ./gradlew && ./gradlew --no-daemon --quiet help

# Источники и ресурсы (пулы лежат в src/main/resources/pool*/ и попадают в jar)
COPY src ./src

RUN ./gradlew --no-daemon --quiet shadowJar \
    && cp build/libs/faker-llm-all.jar /workspace/faker-llm-all.jar

# ----- runtime stage ------------------------------------------------
# Тонкий JRE-образ, без Gradle и исходников.
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl нужен для HEALTHCHECK; tini — корректный init для PID 1 (signal-handling).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tini \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Не-root юзер для прода
RUN groupadd --system --gid 1000 faker \
    && useradd --system --uid 1000 --gid faker --create-home --home-dir /home/faker faker \
    && mkdir -p /app/logs \
    && chown -R faker:faker /app

COPY --from=build --chown=faker:faker /workspace/faker-llm-all.jar /app/faker-llm-all.jar

USER faker

# Env defaults — переопределяются через docker run -e / compose environment
ENV PORT=8080 \
    FAKER_POOL_DIR=pool \
    LOG_DIR=/app/logs \
    JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:MaxRAMPercentage=75 -Dkotlinx.coroutines.scheduler.max.pool.size=256"

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/healthz" || exit 1

# tini как init → корректная пересылка SIGTERM в JVM при docker stop
ENTRYPOINT ["/usr/bin/tini", "--"]
# JAVA_OPTS разворачиваем через sh -c (иначе exec не сделает word-splitting)
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/faker-llm-all.jar"]
