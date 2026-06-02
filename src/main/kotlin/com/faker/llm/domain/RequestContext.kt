package com.faker.llm.domain

/**
 * Normalized, provider-agnostic view of an inbound request.
 *
 * Adapters parse provider-specific payloads (OpenAI `messages[]`,
 * Anthropic `messages[]` + `system`) and populate this struct; the core
 * (`PoolSelector`, `RequestRouter`, `StreamingEngine`) reads it.
 *
 * Intentionally *runtime-only* — not `@Serializable`.
 *
 * @param hasTools whether the request declared a non-empty `tools` array
 * @param toolNames flattened list of tool names from the request (engine picks one randomly)
 * @param stream whether the client asked for a streaming response
 * @param model the requested model identifier, if present
 * @param inspectableContent concatenated user/system text content used by
 *   [com.faker.llm.routing] to scan for `[[faker:...]]` directives. `null` when
 *   the endpoint has no inspectable text (e.g. structured-only requests).
 * @param directiveHeader raw value of the `X-Faker-Directive` HTTP header (JSON payload),
 *   if the client sent one. Consumed by
 *   [com.faker.llm.routing.policies.HeaderDirectivePolicy] to synthesize errors.
 *   `null` when the header is absent — the only HTTP metadata leaking into the
 *   provider-agnostic context, kept opt-in for routing only.
 */
data class RequestContext(
    val hasTools: Boolean,
    val toolNames: List<String>,
    val stream: Boolean,
    val model: String?,
    val inspectableContent: String?,
    val directiveHeader: String? = null,
)
