package ai.rever.boss.plugin.dynamic.llmcost

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Persistent record of every [CostCall] the tracker has seen, plus the
 * configured monthly budget.
 *
 * Storage rides [PluginStorageProvider]: the host scopes writes per plugin,
 * so the keys here are owned exclusively by this plugin and survive restarts.
 * Two slots: a JSON-encoded list under [CALLS_KEY] and a plain double under
 * [BUDGET_KEY]. The list is bounded - oldest-first eviction at
 * [MAX_CALLS] keeps a long-running install from growing unbounded, and the
 * mutex serialises reads against in-flight appends so the in-memory mirror
 * never observes a half-written list.
 *
 * The in-memory mirror ([calls]) is the source of truth for the UI; every
 * mutation refreshes it before releasing the lock. Persistence is
 * asynchronous and runs after the in-memory update succeeds, so the UI sees
 * the change even if the disk write fails.
 */
class CostStore(
    private val storage: PluginStorageProvider,
    private val now: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { defaultIdGenerator(now()) },
) {

    private val mutex = Mutex()
    private val _calls = MutableStateFlow<List<CostCall>>(emptyList())
    private val _monthlyBudgetUsd = MutableStateFlow(0.0)

    val calls: StateFlow<List<CostCall>> = _calls.asStateFlow()
    val monthlyBudgetUsd: StateFlow<Double> = _monthlyBudgetUsd.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load() {
        mutex.withLock {
            val raw = storage.getJson(CALLS_KEY)
            val loadedCalls = if (raw.isNullOrEmpty()) {
                emptyList()
            } else {
                runCatching { json.decodeFromString<List<CostCall>>(raw) }.getOrDefault(emptyList())
            }
            _calls.value = loadedCalls.takeLast(MAX_CALLS)
            val rawBudget = storage.getString(BUDGET_KEY, "0.0")
            _monthlyBudgetUsd.value = rawBudget?.toDoubleOrNull() ?: 0.0
        }
    }

    suspend fun append(call: CostCall) {
        val updated = mutex.withLock {
            val current = _calls.value
            val next = if (current.size >= MAX_CALLS) {
                current.drop(current.size - MAX_CALLS + 1) + call
            } else {
                current + call
            }
            _calls.value = next
            next
        }
        persistCalls(updated)
    }

    suspend fun setMonthlyBudgetUsd(value: Double) {
        val bounded = value.coerceIn(0.0, MAX_BUDGET_USD)
        mutex.withLock { _monthlyBudgetUsd.value = bounded }
        storage.putString(BUDGET_KEY, bounded.toString())
    }

    suspend fun clearAll() {
        mutex.withLock {
            _calls.value = emptyList()
            _monthlyBudgetUsd.value = 0.0
        }
        storage.remove(CALLS_KEY)
        storage.remove(BUDGET_KEY)
    }

    private suspend fun persistCalls(list: List<CostCall>) {
        val encoded = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CostCall.serializer()), list)
        storage.putJson(CALLS_KEY, encoded)
    }

    companion object {
        /** Hard cap on the in-memory list - oldest entries are evicted first. */
        const val MAX_CALLS: Int = 50_000

        /** Reject any budget above this as a likely typo. */
        const val MAX_BUDGET_USD: Double = 1_000_000.0

        const val CALLS_KEY: String = "calls.v1"
        const val BUDGET_KEY: String = "monthly_budget_usd"

        private fun defaultIdGenerator(epochMs: Long): String =
            "$epochMs-${(0..0xFFFF).random()}"
    }
}
