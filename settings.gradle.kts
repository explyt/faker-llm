pluginManagement {
    val kotlinVersion: String by settings
    val ktorVersion: String by settings
    val shadowVersion: String by settings

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion
        id("io.ktor.plugin") version ktorVersion
        id("com.gradleup.shadow") version shadowVersion
    }
}

plugins {
    // Auto-provisions the JDK 21 toolchain (local machine ships a newer JDK).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "faker-llm"
