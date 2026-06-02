package com.faker.llm.adapter.openai

import kotlinx.serialization.json.Json

/**
 * Adapter-local [Json] instance. Independent from `PoolJson` (engine-side):
 *  - tolerates unknown request fields (`temperature`, `seed`, `response_format`, ...)
 *  - drops nulls from the wire (per real OpenAI shape) — except where `@EncodeDefault(ALWAYS)`
 *    overrides at the field level (e.g. error `code`/`param`, chunk `index`).
 */
object OpenAiJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        // Default-valued fields are dropped by default; per-field @EncodeDefault(ALWAYS)
        // re-enables writing for the few that must mirror real OpenAI (error code/param,
        // chunk index). Mixing this with explicitNulls=false would override the annotation,
        // so we keep explicitNulls at its default (true).
        encodeDefaults = false
    }
}
