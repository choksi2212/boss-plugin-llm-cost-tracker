package ai.rever.boss.plugin.dynamic.llmcost

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * MCP tools contributed by the LLM cost tracker.
 *
 * Registered in [LlmCostDynamicPlugin.register] via
 * `context.registerMcpToolProvider(...)`, so the `cost_*` tools appear on the
 * `boss` MCP server while this plugin is active and are removed automatically
 * when it is disabled or unloaded. Every handler funnels through the shared
 * [LlmCostViewModel] so the panel, the budget alerts and the MCP surface read
 * the same record.
 *
 * Tool inventory:
 *  - `cost_record(callJson)` records a single call.
 *  - `cost_summary(window)` returns the aggregate for the named window.
 *  - `cost_by_plugin(window)` / `cost_by_model(window)` return breakdown rows.
 *  - `cost_recent(limit)` returns the last N calls, newest first.
 *  - `cost_set_budget(monthlyUsd)` / `cost_get_budget()` manage the budget.
 *  - `cost_export(format)` returns the entire log as CSV or JSON.
 */
internal class LlmCostMcpToolProvider(
    override val providerId: String,
    private val viewModel: LlmCostViewModel,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "cost_record",
            description = "Record one model call into the cost tracker. Required: modelId, " +
                "promptTokens, completionTokens. Optional: callingPluginId, callName, " +
                "timestamp (Unix epoch ms; defaults to now). Returns the recorded call " +
                "with its priced totalCostUsd and the pricing source.",
            inputSchema = RECORD_SCHEMA,
            handler = McpToolHandler { args -> recordCall(args) },
        ),
        McpToolDefinition(
            name = "cost_summary",
            description = "Aggregate spend and tokens for a window: 'today', " +
                "'this_week', 'this_month', or 'last_30_days'. Unknown values fall " +
                "through to 'last_30_days'.",
            inputSchema = WINDOW_SCHEMA,
            handler = McpToolHandler { args -> summary(args) },
        ),
        McpToolDefinition(
            name = "cost_by_plugin",
            description = "Breakdown of recorded calls grouped by calling plugin id " +
                "(calls that did not declare one are reported as '(unknown)'), " +
                "sorted by total cost descending. Optional 'window' argument; " +
                "default 'last_30_days'.",
            inputSchema = WINDOW_SCHEMA,
            handler = McpToolHandler { args -> byPlugin(args) },
        ),
        McpToolDefinition(
            name = "cost_by_model",
            description = "Breakdown of recorded calls grouped by model id, sorted by " +
                "total cost descending. Optional 'window' argument; default " +
                "'last_30_days'.",
            inputSchema = WINDOW_SCHEMA,
            handler = McpToolHandler { args -> byModel(args) },
        ),
        McpToolDefinition(
            name = "cost_recent",
            description = "The most recent recorded calls, newest first. Optional " +
                "'limit' argument; default 50, max 500.",
            inputSchema = LIMIT_SCHEMA,
            handler = McpToolHandler { args -> recent(args) },
        ),
        McpToolDefinition(
            name = "cost_set_budget",
            description = "Set the monthly spend budget in USD. Non-negative; capped at " +
                "1,000,000 USD to refuse obvious typos. Returns the value that was " +
                "stored.",
            inputSchema = BUDGET_SCHEMA,
            handler = McpToolHandler { args -> setBudget(args) },
        ),
        McpToolDefinition(
            name = "cost_get_budget",
            description = "Return the configured monthly budget in USD (0 when none " +
                "has been set).",
            handler = McpToolHandler { _ -> getBudget() },
        ),
        McpToolDefinition(
            name = "cost_export",
            description = "Return every recorded call as a CSV or JSON blob. Format " +
                "argument is 'csv' (default) or 'json'. The blob is suitable for " +
                "writing to a file or pasting into a spreadsheet.",
            inputSchema = FORMAT_SCHEMA,
            handler = McpToolHandler { args -> export(args) },
        ),
    )

    private suspend fun recordCall(args: McpToolArgs): McpToolResult {
        val modelId = args.string("modelId")
            ?: return McpToolResult("Missing required argument: modelId", isError = true)
        val prompt = longArg(args, "promptTokens")
            ?: return McpToolResult("Missing required argument: promptTokens", isError = true)
        val completion = longArg(args, "completionTokens")
            ?: return McpToolResult("Missing required argument: completionTokens", isError = true)
        val timestamp = longArg(args, "timestamp")
        val callingPlugin = args.string("callingPluginId")
        val callName = args.string("callName")

        val result = viewModel.recordCall(
            modelIdRaw = modelId,
            promptTokens = prompt,
            completionTokens = completion,
            callingPluginId = callingPlugin,
            callName = callName,
            timestamp = timestamp,
        )
        return when (result) {
            is CostRecordResult.Ok -> {
                val payload = Json.encodeToString(CostCall.serializer(), result.call)
                McpToolResult("Recorded call. source=${result.source.name}\n$payload")
            }
            is CostRecordResult.Invalid -> McpToolResult("Refused: ${result.reason}", isError = true)
        }
    }

    /**
     * Read an integer-valued argument as Long. McpToolArgs exposes `int()` only,
     * which silently drops values outside the Int range; token counts in
     * practice can exceed Int.MAX_VALUE on long-context runs, so we go through
     * the double getter (which sees the original number) and floor it.
     */
    private fun longArg(args: McpToolArgs, key: String): Long? {
        if (!args.has(key)) return null
        val asInt = args.int(key)
        if (asInt != null) return asInt.toLong()
        val asDouble = args.double(key) ?: return null
        if (!asDouble.isFinite() || asDouble < 0.0) return null
        return asDouble.toLong()
    }

    private fun summary(args: McpToolArgs): McpToolResult {
        val window = CostWindow.fromWire(args.string("window"))
        val aggregates = viewModel.aggregates.value
        val summary = when (window) {
            CostWindow.TODAY -> aggregates.today
            CostWindow.THIS_WEEK -> aggregates.thisWeek
            CostWindow.THIS_MONTH -> aggregates.thisMonth
            CostWindow.LAST_30_DAYS -> aggregates.last30Days
        }
        return McpToolResult(Json.encodeToString(CostSummary.serializer(), summary))
    }

    private fun byPlugin(args: McpToolArgs): McpToolResult {
        val window = CostWindow.fromWire(args.string("window"))
        val calls = viewModel.store.calls.value
        val cutoff = windowCutoff(window)
        val rows = viewModel.aggregates.value.byPlugin.filter { row ->
            calls.any { (it.callingPluginId ?: "(unknown)") == row.pluginId && it.timestamp >= cutoff }
        }
        return McpToolResult(
            Json.encodeToString(
                ListSerializer(CostByPluginRow.serializer()),
                rows,
            ),
        )
    }

    private fun byModel(args: McpToolArgs): McpToolResult {
        val window = CostWindow.fromWire(args.string("window"))
        val calls = viewModel.store.calls.value
        val cutoff = windowCutoff(window)
        val rows = viewModel.aggregates.value.byModel.filter { row ->
            calls.any { it.modelId == row.modelId && it.timestamp >= cutoff }
        }
        return McpToolResult(
            Json.encodeToString(
                ListSerializer(CostByModelRow.serializer()),
                rows,
            ),
        )
    }

    private fun recent(args: McpToolArgs): McpToolResult {
        val requested = args.int("limit") ?: 50
        val limit = requested.coerceIn(1, 500)
        val recent = viewModel.store.calls.value.takeLast(limit).reversed()
        return McpToolResult(
            Json.encodeToString(
                ListSerializer(CostCall.serializer()),
                recent,
            ),
        )
    }

    private suspend fun setBudget(args: McpToolArgs): McpToolResult {
        val raw = args.double("monthlyUsd")
            ?: return McpToolResult("Missing required argument: monthlyUsd", isError = true)
        val stored = viewModel.setBudget(raw)
        return if (stored != null) {
            McpToolResult("budget=${BudgetTracker.formatUsd(stored)}")
        } else {
            McpToolResult("Refused: budget must be between 0 and ${CostStore.MAX_BUDGET_USD.toLong()} USD.", isError = true)
        }
    }

    private fun getBudget(): McpToolResult {
        val value = viewModel.store.monthlyBudgetUsd.value
        return McpToolResult(Json.encodeToString(BudgetHolder.serializer(), BudgetHolder(value)))
    }

    private fun export(args: McpToolArgs): McpToolResult {
        val format = args.string("format")?.lowercase() ?: "csv"
        val calls = viewModel.store.calls.value
        val payload = when (format) {
            "json" -> Json.encodeToString(
                ListSerializer(CostCall.serializer()),
                calls,
            )
            "csv" -> CsvExporter.toCsv(calls)
            else -> return McpToolResult(
                "Unsupported format '$format'. Use 'csv' or 'json'.",
                isError = true,
            )
        }
        return McpToolResult(payload)
    }

    /**
     * Cutoff timestamp for the requested window. Calls at or after this
     * instant count toward the window; LAST_30_DAYS returns 0L so every
     * persisted call is included.
     */
    private fun windowCutoff(window: CostWindow): Long {
        if (window == CostWindow.LAST_30_DAYS) return 0L
        val now = System.currentTimeMillis()
        return when (window) {
            CostWindow.TODAY -> startOfDay(now)
            CostWindow.THIS_WEEK -> now - TimeUnit.DAYS.toMillis(7)
            CostWindow.THIS_MONTH -> now - TimeUnit.DAYS.toMillis(30)
            else -> 0L
        }
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

    private companion object {
        const val RECORD_SCHEMA =
            """{"type":"object","properties":{"modelId":{"type":"string"},"promptTokens":{"type":"integer"},"completionTokens":{"type":"integer"},"callingPluginId":{"type":"string"},"callName":{"type":"string"},"timestamp":{"type":"integer"}},"required":["modelId","promptTokens","completionTokens"]}"""
        const val WINDOW_SCHEMA =
            """{"type":"object","properties":{"window":{"type":"string","enum":["today","this_week","this_month","last_30_days"]}}}"""
        const val LIMIT_SCHEMA =
            """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":500}}}"""
        const val BUDGET_SCHEMA =
            """{"type":"object","properties":{"monthlyUsd":{"type":"number","minimum":0}},"required":["monthlyUsd"]}"""
        const val FORMAT_SCHEMA =
            """{"type":"object","properties":{"format":{"type":"string","enum":["csv","json"]}}}"""
    }
}

@kotlinx.serialization.Serializable
private data class BudgetHolder(val monthlyUsd: Double)
