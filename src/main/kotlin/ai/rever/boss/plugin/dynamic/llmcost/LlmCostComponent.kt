package ai.rever.boss.plugin.dynamic.llmcost

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * Panel component for the LLM cost tracker.
 *
 * Decompose component context is delegated in by Kotlin's `by`-clause so the
 * host can compose this once and own its lifecycle; the panel UI delegates
 * to [LlmCostContent], which reads from the shared [LlmCostViewModel].
 */
class LlmCostComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val viewModel: LlmCostViewModel,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        LlmCostContent(viewModel = viewModel)
    }
}
