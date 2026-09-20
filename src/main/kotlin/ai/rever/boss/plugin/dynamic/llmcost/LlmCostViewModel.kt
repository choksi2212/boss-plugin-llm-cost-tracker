package ai.rever.boss.plugin.dynamic.llmcost

import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-side glue between the [CostStore], the [Pricing] table, the
 * [BudgetTracker], and the UI.
 *
 * Owns the cost-record write path: every MCP `cost_record` and every panel
 * import flows through [recordCall] which prices, evicts, persists, then
 * evaluates the budget - in that order - so the budget sees the cost it just
 * recorded. [aggregates] is the single derived flow the panel renders from;
 * it folds calls into today / this month / last 30 days, plus per-plugin and
 * per-model breakdowns, in one pass so the UI never recomputes them.
 *
 * Suspending mutations serialise through [mutationMutex] so a flurry of MCP
 * calls cannot interleave a write to the store with a budget evaluation and
 * double-fire alerts.
 */
class LlmCostViewModel(
    val store: CostStore,
    val pricing: Pricing,
    val notifier: NotificationProvider?,
    private val windowId: String = "",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutationMutex = Mutex()
    private val budgetTracker = BudgetTracker()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val aggregates: StateFlow<Aggregates> = combine(
        store.calls,
        store.monthlyBudgetUsd,
    ) { calls, budget -> computeAggregates(calls, budget) }
        .stateIn(scope, SharingStarted.Eagerly, Aggregates.Empty)

    init {
        scope.launch { store.load() }
    }

    suspend fun recordCall(
        modelIdRaw: String?,
        promptTokens: Long,
        completionTokens: Long,
        callingPluginId: String?,
        callName: String?,
        timestamp: Long?,
    ): CostRecordResult {
        val modelId = modelIdRaw?.take(MAX_MODEL_ID_LENGTH)?.trim().orEmpty()
        if (modelId.isEmpty()) {
            return CostRecordResult.Invalid("modelId is required")
        }
        if (promptTokens < 0 || completionTokens < 0) {
            return CostRecordResult.Invalid("token counts must be non-negative")
        }

        val safePrompt = promptTokens.coerceAtMost(MAX_TOKEN_COUNT)
        val safeCompletion = completionTokens.coerceAtMost(MAX_TOKEN_COUNT)
        val safePlugin = callingPluginId?.take(MAX_PLUGIN_ID_LENGTH)?.trim().orEmpty().ifEmpty { null }
        val safeName = callName?.take(MAX_CALL_NAME_LENGTH)?.trim().orEmpty().ifEmpty { null }
        val ts = timestamp?.takeIf { it > 0L } ?: System.currentTimeMillis()

        val priced = pricing.priceCall(modelId, safePrompt, safeCompletion)

        val call = CostCall(
            id = "$ts-${(0..0xFFFF).random()}",
            timestamp = ts,
            modelId = modelId,
            promptTokens = safePrompt,
            completionTokens = safeCompletion,
            totalCostUsd = priced.totalCostUsd,
            callingPluginId = safePlugin,
            callName = safeName,
        )

        mutationMutex.withLock {
            store.append(call)
            val alerts = budgetTracker.evaluate(store, notifier)
            for (alert in alerts) {
                store.append(alert)
            }
        }

        return CostRecordResult.Ok(call, priced.source)
    }

    suspend fun setBudget(monthlyUsd: Double): Double? {
        if (monthlyUsd < 0.0) {
            _errorMessage.value = "Budget must be non-negative"
            return null
        }
        if (monthlyUsd > CostStore.MAX_BUDGET_USD) {
            _errorMessage.value = "Budget must be <= ${CostStore.MAX_BUDGET_USD.toLong()}"
            return null
        }
        mutationMutex.withLock {
            store.setMonthlyBudgetUsd(monthlyUsd)
        }
        _statusMessage.value = "Budget set to ${BudgetTracker.formatUsd(monthlyUsd)}"
        return monthlyUsd
    }

    suspend fun clearAll() {
        mutationMutex.withLock { store.clearAll() }
        _statusMessage.value = "All recorded calls cleared"
    }

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    /**
     * Mark an export request from the panel toolbar so the user knows the
     * button registered. The actual payload is built by the MCP tool (or by
     * whatever copied the result); the toolbar's affordance cannot write a
     * file directly without a clipboard / file-pick host call.
     */
    fun markExported(format: String) {
        _statusMessage.value = "Use the cost_export MCP tool to download as $format."
    }

    private fun computeAggregates(calls: List<CostCall>, budget: Double): Aggregates {
        if (calls.isEmpty()) return Aggregates.Empty.copy(budget = budget)
        val now = System.currentTimeMillis()
        val startOfToday = startOfDay(now)
        val startOfWeek = startOfDay(now - DAY_MS * dayOfWeekOffset(now))
        val startOfMonth = budgetTracker.monthStartEpochMs(now)
        val startOf30 = now - 30L * DAY_MS

        val todayCalls = calls.filter { it.timestamp >= startOfToday }
        val weekCalls = calls.filter { it.timestamp >= startOfWeek }
        val monthCalls = calls.filter { it.timestamp >= startOfMonth }
        val last30Calls = calls.filter { it.timestamp >= startOf30 }

        val byPlugin = calls.groupBy { it.callingPluginId ?: "(unknown)" }
            .map { (id, list) ->
                CostByPluginRow(
                    pluginId = id,
                    calls = list.size,
                    promptTokens = list.sumOf { it.promptTokens },
                    completionTokens = list.sumOf { it.completionTokens },
                    totalCostUsd = list.sumOf { it.totalCostUsd },
                )
            }
            .sortedByDescending { it.totalCostUsd }

        val byModel = calls.groupBy { it.modelId }
            .map { (model, list) ->
                CostByModelRow(
                    modelId = model,
                    calls = list.size,
                    totalTokens = list.sumOf { it.totalTokens },
                    totalCostUsd = list.sumOf { it.totalCostUsd },
                )
            }
            .sortedByDescending { it.totalCostUsd }

        val monthSpend = monthCalls.filter { it.callName != BudgetTracker.BUDGET_ALERT_NAME }
            .sumOf { it.totalCostUsd }
        val monthTokens = monthCalls.sumOf { it.totalTokens }

        return Aggregates(
            budget = budget,
            today = CostSummary(
                window = "today",
                calls = todayCalls.size,
                promptTokens = todayCalls.sumOf { it.promptTokens },
                completionTokens = todayCalls.sumOf { it.completionTokens },
                totalTokens = todayCalls.sumOf { it.totalTokens },
                totalCostUsd = todayCalls.sumOf { it.totalCostUsd },
            ),
            thisWeek = CostSummary(
                window = "this_week",
                calls = weekCalls.size,
                promptTokens = weekCalls.sumOf { it.promptTokens },
                completionTokens = weekCalls.sumOf { it.completionTokens },
                totalTokens = weekCalls.sumOf { it.totalTokens },
                totalCostUsd = weekCalls.sumOf { it.totalCostUsd },
            ),
            thisMonth = CostSummary(
                window = "this_month",
                calls = monthCalls.size,
                promptTokens = monthCalls.sumOf { it.promptTokens },
                completionTokens = monthCalls.sumOf { it.completionTokens },
                totalTokens = monthCalls.sumOf { it.totalTokens },
                totalCostUsd = monthCalls.sumOf { it.totalCostUsd },
            ),
            last30Days = CostSummary(
                window = "last_30_days",
                calls = last30Calls.size,
                promptTokens = last30Calls.sumOf { it.promptTokens },
                completionTokens = last30Calls.sumOf { it.completionTokens },
                totalTokens = last30Calls.sumOf { it.totalTokens },
                totalCostUsd = last30Calls.sumOf { it.totalCostUsd },
            ),
            monthSpendUsd = monthSpend,
            monthTokens = monthTokens,
            budgetProgressPercent = if (budget > 0.0) (monthSpend / budget).coerceAtLeast(0.0) else 0.0,
            byPlugin = byPlugin,
            byModel = byModel,
            recent = calls.takeLast(50).reversed(),
        )
    }

    private fun startOfDay(epochMs: Long): Long {
        val zone = java.util.TimeZone.getDefault()
        val cal = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = epochMs
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun dayOfWeekOffset(epochMs: Long): Int {
        val zone = java.util.TimeZone.getDefault()
        val cal = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = epochMs
            firstDayOfWeek = java.util.Calendar.MONDAY
        }
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        // Convert Sunday=1..Saturday=7 to Monday=0..Sunday=6
        return ((dow - java.util.Calendar.MONDAY) + 7) % 7
    }

    companion object {
        const val MAX_MODEL_ID_LENGTH: Int = 128
        const val MAX_PLUGIN_ID_LENGTH: Int = 128
        const val MAX_CALL_NAME_LENGTH: Int = 256
        const val MAX_TOKEN_COUNT: Long = 1_000_000_000L
        private const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    }
}

/** All derived numbers the panel renders. */
data class Aggregates(
    val budget: Double,
    val today: CostSummary,
    val thisWeek: CostSummary,
    val thisMonth: CostSummary,
    val last30Days: CostSummary,
    val monthSpendUsd: Double,
    val monthTokens: Long,
    val budgetProgressPercent: Double,
    val byPlugin: List<CostByPluginRow>,
    val byModel: List<CostByModelRow>,
    val recent: List<CostCall>,
) {
    companion object {
        val Empty = Aggregates(
            budget = 0.0,
            today = CostSummary("today", 0, 0, 0, 0, 0.0),
            thisWeek = CostSummary("this_week", 0, 0, 0, 0, 0.0),
            thisMonth = CostSummary("this_month", 0, 0, 0, 0, 0.0),
            last30Days = CostSummary("last_30_days", 0, 0, 0, 0, 0.0),
            monthSpendUsd = 0.0,
            monthTokens = 0L,
            budgetProgressPercent = 0.0,
            byPlugin = emptyList(),
            byModel = emptyList(),
            recent = emptyList(),
        )
    }
}

/** Outcome of one [LlmCostViewModel.recordCall] invocation. */
sealed class CostRecordResult {
    data class Ok(val call: CostCall, val source: Pricing.Source) : CostRecordResult()
    data class Invalid(val reason: String) : CostRecordResult()
}

/** Build the view model from the host's storage and notifier, both nullable. */
fun buildViewModel(
    storageProvider: PluginStorageProvider?,
    llmProvider: ai.rever.boss.plugin.api.LlmProvider?,
    notifier: NotificationProvider?,
    windowId: String = "",
): LlmCostViewModel {
    val store = if (storageProvider != null) CostStore(storageProvider) else CostStore(NoopStorageProvider)
    val pricing = Pricing(llmProvider)
    return LlmCostViewModel(store, pricing, notifier, windowId)
}

/** In-memory storage provider used when the host cannot supply one. */
private object NoopStorageProvider : PluginStorageProvider {
    override fun getPluginId(): String = "ai.rever.boss.plugin.dynamic.llmcost"
    override suspend fun putString(key: String, value: String) {}
    override suspend fun getString(key: String, defaultValue: String?): String? = defaultValue
    override suspend fun putInt(key: String, value: Int) {}
    override suspend fun getInt(key: String, defaultValue: Int): Int = defaultValue
    override suspend fun putLong(key: String, value: Long) {}
    override suspend fun getLong(key: String, defaultValue: Long): Long = defaultValue
    override suspend fun putBoolean(key: String, value: Boolean) {}
    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
    override suspend fun putFloat(key: String, value: Float) {}
    override suspend fun getFloat(key: String, defaultValue: Float): Float = defaultValue
    override suspend fun putJson(key: String, jsonValue: String) {}
    override suspend fun getJson(key: String): String? = null
    override suspend fun contains(key: String): Boolean = false
    override suspend fun remove(key: String) {}
    override suspend fun getAllKeys(): Set<String> = emptySet()
    override suspend fun clear() {}
    override fun observeString(key: String): kotlinx.coroutines.flow.Flow<String?> =
        kotlinx.coroutines.flow.flowOf(null)
    override fun observeChanges(): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.emptyFlow()
}
