package ai.rever.boss.plugin.dynamic.llmcost

import kotlinx.serialization.Serializable

/**
 * One recorded inference call, the unit of cost aggregation.
 *
 * [id] is a stable opaque string the storage layer mints; UI rows and exports
 * quote it verbatim. [timestamp] is the call's recorded wall-clock instant in
 * Unix epoch milliseconds; the recorder fills it when omitted. [modelId] is the
 * model the call ran against and the key [Pricing] uses to look up USD rates.
 * [promptTokens] and [completionTokens] are the two counts the host's
 * `AiModelPricing` models; everything the tracker aggregates is derived from
 * them. [totalCostUsd] is the priced answer at record time and is what the
 * budget watcher sums against. [callingPluginId] and [callName] are optional
 * provenance: a plugin that records its own calls may tag them, anything that
 * forwards through `cost_record` may pass a free-form name.
 */
@Serializable
data class CostCall(
    val id: String,
    val timestamp: Long,
    val modelId: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalCostUsd: Double,
    val callingPluginId: String? = null,
    val callName: String? = null,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/** Aggregate for one row of `cost_by_plugin`. */
@Serializable
data class CostByPluginRow(
    val pluginId: String,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalCostUsd: Double,
)

/** Aggregate for one row of `cost_by_model`. */
@Serializable
data class CostByModelRow(
    val modelId: String,
    val calls: Int,
    val totalTokens: Long,
    val totalCostUsd: Double,
)

/** Aggregate for one window of `cost_summary`. */
@Serializable
data class CostSummary(
    val window: String,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val totalCostUsd: Double,
)

/**
 * Window string accepted by `cost_summary` / `cost_by_plugin` / `cost_by_model`.
 *
 * Stored verbatim - the matcher below is the single definition of what each
 * value means, so renaming one is a one-line edit and the JSON row carries
 * the original form.
 */
enum class CostWindow(val wire: String) {
    TODAY("today"),
    THIS_WEEK("this_week"),
    THIS_MONTH("this_month"),
    LAST_30_DAYS("last_30_days");

    companion object {
        fun fromWire(value: String?): CostWindow = when (value?.lowercase()) {
            "today" -> TODAY
            "this_week" -> THIS_WEEK
            "this_month" -> THIS_MONTH
            "last_30_days" -> LAST_30_DAYS
            else -> LAST_30_DAYS
        }
    }
}
