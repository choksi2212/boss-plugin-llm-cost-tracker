package ai.rever.boss.plugin.dynamic.llmcost

import ai.rever.boss.plugin.api.NotificationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory monitor that watches the running monthly spend against the
 * configured budget and emits a one-shot alert the first time each threshold
 * is crossed.
 *
 * The thresholds are 80% and 100% of the budget; the alert is a synthetic
 * [CostCall] with `callName = "budget_alert"` so it appears in the
 * recent-calls list, plus a host toast (when a [NotificationProvider] is
 * available) so the user sees something. The synthetic call is appended to the
 * store the same way a real one is, so the alert's contribution to the
 * running total is recorded but the dollar value it carries is zero - it does
 * NOT inflate the spend it is warning about.
 *
 * Crossing is sticky per calendar month: once an alert has fired for a
 * threshold it does not fire again until the month rolls over. Lowering the
 * budget mid-month can re-cross a threshold and refire.
 */
class BudgetTracker(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _lastFired = MutableStateFlow<Pair<String, Long>?>(null)
    val lastFired: StateFlow<Pair<String, Long>?> = _lastFired.asStateFlow()

    private var lastEvaluatedMonthKey: String = ""

    /**
     * Evaluate the running spend against [monthlyBudgetUsd] and produce the
     * alerts (if any) that crossed thresholds. The caller forwards the
     * returned alerts to [CostStore.append] - we deliberately don't take a
     * recorder here so the suspend boundary lives where it already does.
     */
    fun evaluate(
        store: CostStore,
        notifier: NotificationProvider?,
        nowMs: Long = now(),
    ): List<CostCall> {
        val monthKey = monthKeyFor(nowMs)
        if (monthKey != lastEvaluatedMonthKey) {
            lastEvaluatedMonthKey = monthKey
        }

        val budget = store.monthlyBudgetUsd.value
        if (budget <= 0.0) return emptyList()

        val spendThisMonth = sumForMonth(store.calls.value, nowMs)
        if (spendThisMonth <= 0.0) return emptyList()

        val percent = spendThisMonth / budget
        val alerts = mutableListOf<CostCall>()

        if (percent >= WARN_THRESHOLD) {
            val warnKey = "$monthKey:$WARN_LABEL"
            if (_lastFired.value?.first != warnKey) {
                alerts.add(buildAlert(nowMs, WARN_LABEL, budget, spendThisMonth, percent))
                notifier?.showWarning(
                    message = "LLM cost tracker: $WARN_LABEL of monthly budget reached " +
                        "(${formatUsd(spendThisMonth)} of ${formatUsd(budget)}).",
                    title = "Budget alert",
                )
                _lastFired.value = warnKey to nowMs
            }
        }
        if (percent >= OVER_THRESHOLD) {
            val overKey = "$monthKey:$OVER_LABEL"
            if (_lastFired.value?.first != overKey) {
                alerts.add(buildAlert(nowMs, OVER_LABEL, budget, spendThisMonth, percent))
                notifier?.showError(
                    message = "LLM cost tracker: monthly budget exceeded " +
                        "(${formatUsd(spendThisMonth)} of ${formatUsd(budget)}).",
                    title = "Budget exceeded",
                )
                _lastFired.value = overKey to nowMs
            }
        }

        return alerts
    }

    fun monthStartEpochMs(anchor: Long): Long {
        val zone = java.util.TimeZone.getDefault()
        val cal = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = anchor
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun monthKeyFor(anchor: Long): String {
        val zone = java.util.TimeZone.getDefault()
        val cal = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = anchor
        }
        return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH) + 1}"
    }

    private fun sumForMonth(calls: List<CostCall>, anchor: Long): Double {
        val monthStart = monthStartEpochMs(anchor)
        return calls.asSequence()
            .filter { it.timestamp >= monthStart && it.callName != BUDGET_ALERT_NAME }
            .sumOf { it.totalCostUsd }
    }

    private fun buildAlert(
        timestamp: Long,
        label: String,
        budget: Double,
        spend: Double,
        percent: Double,
    ): CostCall = CostCall(
        id = "budget-alert-${timestamp}-${(0..0xFFFF).random()}",
        timestamp = timestamp,
        modelId = "budget",
        promptTokens = 0L,
        completionTokens = 0L,
        totalCostUsd = 0.0,
        callingPluginId = "ai.rever.boss.plugin.dynamic.llmcost",
        callName = "$BUDGET_ALERT_NAME:$label:${formatPercent(percent)}:" +
            "${formatUsd(spend)}/${formatUsd(budget)}",
    )

    companion object {
        const val WARN_THRESHOLD: Double = 0.80
        const val OVER_THRESHOLD: Double = 1.00

        const val WARN_LABEL: String = "warn_80"
        const val OVER_LABEL: String = "exceeded_100"
        const val BUDGET_ALERT_NAME: String = "budget_alert"

        fun formatUsd(value: Double): String = "$%.2f".format(value)

        fun formatPercent(value: Double): String = "%.0f%%".format(value * 100.0)
    }
}
