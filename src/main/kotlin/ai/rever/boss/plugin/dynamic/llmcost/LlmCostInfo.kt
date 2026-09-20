package ai.rever.boss.plugin.dynamic.llmcost

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.DollarSign

/**
 * Panel info for the LLM cost tracker sidebar item.
 *
 * Default slot is `left.bottom` so the panel sits with the other observability
 * surfaces. Priority 90 keeps it after the workspace / git-change items but
 * above unrelated tail entries.
 */
object LlmCostInfo : PanelInfo {
    override val id = PanelId("llm-cost", 90, pluginId = "ai.rever.boss.plugin.dynamic.llmcost")
    override val displayName = "LLM Cost"
    override val icon = FeatherIcons.DollarSign
    override val defaultSlotPosition = left.bottom
}
