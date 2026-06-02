package com.faker.llm.adapter.anthropic

import kotlinx.serialization.json.Json

/**
 * Adapter-local [Json] instance for Anthropic. Independent from `PoolJson` and `OpenAiJson`.
 *
 * Key trade-offs (mirror what we learned in the OpenAI adapter):
 *  - `ignoreUnknownKeys = true` — tolerate request extensions (`temperature`, `top_p`, ...).
 *  - `encodeDefaults = false` — drop default-valued fields by default; the few wire-required
 *    ones opt in via `@EncodeDefault(ALWAYS)` (literal `type` strings, `role`, `content=[]`,
 *    explicit `null` for `stop_reason` / `stop_sequence` inside `message_start.message`).
 *  - `classDiscriminator` is left at the default (`"type"`) — both `AnthropicContentBlock`
 *    and `ContentBlockDelta` already use `"type"`, so no override is needed.
 */
object AnthropicJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
}
