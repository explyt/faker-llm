package com.faker.llm.pool

import kotlinx.serialization.json.Json

/**
 * Single shared [Json] instance for pool (de)serialization, reused by the loader and adapters.
 *
 * Discriminators are annotation-driven per the locked Task 02 contract:
 * - `PoolEntry` → `"kind"` (via `@JsonClassDiscriminator` on the sealed interface)
 * - `ResponsePart` → `"type"` (kotlinx default)
 *
 * We deliberately do NOT override [kotlinx.serialization.json.JsonBuilder.classDiscriminator]
 * globally: doing so would also flip `ResponsePart`'s discriminator to `"kind"`, breaking the
 * contract verified in Task 02. Sealed hierarchies auto-register their subtypes, so no manual
 * `SerializersModule` is required.
 */
object PoolJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        // Pool files are read, never written by the service — pretty printing is irrelevant.
    }
}
