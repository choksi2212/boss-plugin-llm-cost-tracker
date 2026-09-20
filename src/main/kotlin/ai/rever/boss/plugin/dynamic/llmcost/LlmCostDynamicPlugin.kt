package ai.rever.boss.plugin.dynamic.llmcost

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageProvider

/**
 * LLM cost tracker dynamic plugin - loaded from an external JAR.
 *
 * Aggregates every model call recorded through `cost_record` (or the panel's
 * import path) and renders the running totals as a sidebar panel. Owns one
 * [LlmCostViewModel] for the lifetime of the plugin; the host instantiates
 * one [LlmCostComponent] per panel placement through the registered factory.
 *
 * Provider nullability is deliberate: storage, the LLM pricing source and the
 * notifier can all be absent on a host that does not implement them, and the
 * plugin still loads - the panel renders and the MCP tools respond.
 */
class LlmCostDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.llmcost"
    override val displayName: String = "LLM Cost Tracker"
    override val version: String = "0.1.0"
    override val description: String =
        "Per-call LLM token and cost tracker - aggregates by plugin, by model, " +
            "by day, with monthly budget alerts."
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/choksi2212/boss-plugin-llm-cost-tracker"

    @Volatile
    private var viewModel: LlmCostViewModel? = null

    override fun register(context: PluginContext) {
        val storage: PluginStorageProvider? =
            context.pluginStorageFactory?.createStorage(pluginId)
        val notifier: NotificationProvider? = context.notificationProvider
        val llmProvider = context.llmProvider
        val vm = buildViewModel(
            storageProvider = storage,
            llmProvider = llmProvider,
            notifier = notifier,
            windowId = context.windowId.orEmpty(),
        )
        viewModel = vm

        context.panelRegistry.registerPanel(LlmCostInfo) { ctx, panelInfo ->
            LlmCostComponent(ctx, panelInfo, vm)
        }

        context.registerMcpToolProvider(LlmCostMcpToolProvider(pluginId, vm))
    }

    override fun dispose() {
        viewModel = null
    }
}
