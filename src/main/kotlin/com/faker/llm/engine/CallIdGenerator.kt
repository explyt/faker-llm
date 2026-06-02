package com.faker.llm.engine

import kotlin.random.Random

/**
 * Generates a `call_<24-char alphanumeric>` identifier for a tool call.
 *
 * Mirrors the shape returned by real providers (OpenAI uses 24-char ids after the
 * `call_` prefix; Anthropic uses the same value for matching `content_block_start`
 * and `content_block_stop`). The RNG is injected so tests can be deterministic.
 *
 * No cryptographic strength required — this is a fake.
 */
internal object CallIdGenerator {
    private const val PREFIX = "call_"
    private const val ID_LENGTH = 24
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun next(random: Random): String = buildString(PREFIX.length + ID_LENGTH) {
        append(PREFIX)
        repeat(ID_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
