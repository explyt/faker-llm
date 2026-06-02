package com.faker.llm.pool

/**
 * Thrown by [PoolSelector.pick] when filtering leaves no applicable entry.
 *
 * Intentionally surfaced as HTTP 500 by the adapters — a typo'd `[[faker:force_id:...]]`
 * directive or a misconfigured pool should be loud and visible to the client, not silently
 * swallowed by a fallback.
 */
class EmptyPoolException(message: String) : RuntimeException(message)
