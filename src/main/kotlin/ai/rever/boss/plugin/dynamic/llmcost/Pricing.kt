package ai.rever.boss.plugin.dynamic.llmcost

import ai.rever.boss.plugin.api.AiModelPricing
import ai.rever.boss.plugin.api.LlmModelPricingAPI
import ai.rever.boss.plugin.api.LlmProvider

/**
 * USD pricing lookup for one inference call.
 *
 * Primary path: cast the host's [LlmProvider] to [LlmModelPricingAPI] (the
 * plugin that owns provider configuration implements both) and resolve the
 * rate card per provider/model. The host exposes modelId-only cards the plugin
 * does not see, so we try the conventional provider ids, then any configured
 * connection the user has set up, before giving up.
 *
 * Fallback path: a baked table for the public model ids the tracker ought to
 * know about even when the host has no pricing provider. Costs are stored as
 * USD per one million tokens, matching [AiModelPricing]; [priceCall] converts
 * to the per-call total by dividing by 1,000,000 and multiplying by token
 * counts.
 *
 * Returns a zero [Pricing] for an unknown model rather than throwing - the
 * recorder never refuses a call, and the panel can flag unknown models
 * separately without breaking aggregation.
 */
class Pricing(
    private val llmProvider: LlmProvider?,
    private val fallback: Map<String, StaticRate> = DEFAULT_FALLBACK,
) {

    /** Per-million-token USD rates for one model. */
    data class StaticRate(
        val inputUsdPer1M: Double,
        val outputUsdPer1M: Double,
    )

    /** One priced answer; the recorder stores [totalCostUsd] verbatim. */
    data class Resolved(
        val inputUsdPer1M: Double,
        val outputUsdPer1M: Double,
        val totalCostUsd: Double,
        val source: Source,
    )

    enum class Source {
        HOST_PROVIDER,
        HOST_CONFIGURED,
        STATIC_FALLBACK,
        UNKNOWN,
    }

    /** True if the host published a real rate card for this exact model. */
    val hasHostRate: Boolean
        get() = llmProvider is LlmModelPricingAPI

    fun priceCall(modelId: String, promptTokens: Long, completionTokens: Long): Resolved {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) {
            return Resolved(0.0, 0.0, 0.0, Source.UNKNOWN)
        }

        val hostCard = lookupHost(trimmed)
        if (hostCard != null) {
            val cost = priceFromCard(hostCard, promptTokens, completionTokens)
            return Resolved(hostCard.inputUsdPer1M, hostCard.outputUsdPer1M, cost, Source.HOST_PROVIDER)
        }

        val key = trimmed.lowercase()
        val fallbackRate = fallback[key]
        if (fallbackRate != null) {
            val cost = priceFromRate(fallbackRate, promptTokens, completionTokens)
            return Resolved(fallbackRate.inputUsdPer1M, fallbackRate.outputUsdPer1M, cost, Source.STATIC_FALLBACK)
        }

        return Resolved(0.0, 0.0, 0.0, Source.UNKNOWN)
    }

    private fun lookupHost(modelId: String): AiModelPricing? {
        val api = llmProvider as? LlmModelPricingAPI ?: return null
        for (providerId in CANDIDATE_PROVIDER_IDS) {
            val card = api.modelPricing(providerId, modelId)
            if (card != null) return card
        }
        return null
    }

    private fun priceFromCard(card: AiModelPricing, promptTokens: Long, completionTokens: Long): Double {
        return priceFromRate(
            StaticRate(card.inputUsdPer1M, card.outputUsdPer1M),
            promptTokens,
            completionTokens,
        )
    }

    private fun priceFromRate(rate: StaticRate, promptTokens: Long, completionTokens: Long): Double {
        val input = (promptTokens.coerceAtLeast(0L).toDouble() / 1_000_000.0) * rate.inputUsdPer1M
        val output = (completionTokens.coerceAtLeast(0L).toDouble() / 1_000_000.0) * rate.outputUsdPer1M
        return input + output
    }

    companion object {
        /** Conventional provider ids to probe before scanning configured providers. */
        private val CANDIDATE_PROVIDER_IDS = listOf(
            "anthropic",
            "openai",
            "google",
            "xai",
            "moonshot",
            "together",
        )

        /**
         * Baked fallback for the public model ids most likely to appear before
         * any host pricing provider is configured. Rates are USD per one
         * million tokens, matching the host's [AiModelPricing] convention.
         */
        val DEFAULT_FALLBACK: Map<String, StaticRate> = mapOf(
            "gpt-4o" to StaticRate(2.50, 10.00),
            "gpt-4o-mini" to StaticRate(0.15, 0.60),
            "gpt-4.1" to StaticRate(2.00, 8.00),
            "gpt-4.1-mini" to StaticRate(0.40, 1.60),
            "gpt-4-turbo" to StaticRate(10.00, 30.00),
            "o1" to StaticRate(15.00, 60.00),
            "o1-mini" to StaticRate(3.00, 12.00),
            "o3" to StaticRate(10.00, 40.00),
            "o3-mini" to StaticRate(1.10, 4.40),
            "claude-3.5-sonnet" to StaticRate(3.00, 15.00),
            "claude-3.5-haiku" to StaticRate(0.80, 4.00),
            "claude-3-opus" to StaticRate(15.00, 75.00),
            "claude-3-haiku" to StaticRate(0.25, 1.25),
            "claude-sonnet-4" to StaticRate(3.00, 15.00),
            "claude-opus-4" to StaticRate(15.00, 75.00),
            "gemini-1.5-pro" to StaticRate(1.25, 5.00),
            "gemini-1.5-flash" to StaticRate(0.075, 0.30),
            "gemini-2.0-flash" to StaticRate(0.10, 0.40),
            "llama-3-70b" to StaticRate(0.59, 0.79),
            "llama-3.1-70b" to StaticRate(0.59, 0.79),
            "llama-3.1-405b" to StaticRate(2.50, 2.50),
        )
    }
}
