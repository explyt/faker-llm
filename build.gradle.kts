val ktorVersion: String by project
val serializationVersion: String by project
val coroutinesVersion: String by project
val logbackVersion: String by project

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    id("com.gradleup.shadow")
    application
}

group = "com.faker.llm"
version = "0.1.0"

application {
    // Skeleton stub entrypoint. Real Ktor wiring arrives in Task 09.
    mainClass.set("com.faker.llm.MainKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Ktor BOM keeps every ktor artifact on a single consistent version.
    implementation(platform("io.ktor:ktor-bom:$ktorVersion"))

    // --- Server ---
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-sse")

    // --- Serialization & coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // --- Logging ---
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // --- Test (no tests in MVP; wired for future use) ---
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

tasks.test {
    useJUnitPlatform()
}
