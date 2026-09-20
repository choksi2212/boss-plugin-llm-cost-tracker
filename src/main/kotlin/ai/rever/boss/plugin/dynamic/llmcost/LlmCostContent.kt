package ai.rever.boss.plugin.dynamic.llmcost

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full LLM cost tracker sidebar surface.
 *
 * Six sections, top to bottom: today / month summary cards, the budget
 * progress bar, the by-plugin and by-model breakdowns, and the most recent
 * calls. The budget row carries its own inline editor; export and clear
 * live in the toolbar above the cards. The whole thing scrolls vertically
 * so a long recent-calls list never pushes the budget controls off-screen.
 */
@Composable
fun LlmCostContent(viewModel: LlmCostViewModel) {
    BossTheme {
        val aggregates by viewModel.aggregates.collectAsState()
        val status by viewModel.statusMessage.collectAsState()
        val error by viewModel.errorMessage.collectAsState()

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background,
        ) {
            val scope = rememberCoroutineScope()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Toolbar(
                    onExportCsv = { viewModel.markExported("csv") },
                    onExportJson = { viewModel.markExported("json") },
                    onClear = { scope.launch { viewModel.clearAll() } },
                )

                Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.08f))

                Toast(status = status, error = error, onDismiss = { viewModel.clearMessages() })

                SummaryCards(aggregates = aggregates)

                BudgetSection(viewModel = viewModel, aggregates = aggregates)

                BreakdownTables(aggregates = aggregates)

                RecentCalls(calls = aggregates.recent)
            }
        }
    }
}

@Composable
private fun Toolbar(
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LLM Cost",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconAction(icon = Icons.Filled.Download, contentDescription = "Export CSV", onClick = onExportCsv)
        Spacer(modifier = Modifier.width(4.dp))
        IconAction(icon = Icons.Filled.Save, contentDescription = "Export JSON", onClick = onExportJson)
        Spacer(modifier = Modifier.width(4.dp))
        IconAction(icon = Icons.Filled.Delete, contentDescription = "Clear all", onClick = onClear)
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun Toast(status: String?, error: String?, onDismiss: () -> Unit) {
    LaunchedEffect(status, error) {
        delay(2500)
        onDismiss()
    }
    val isError = error != null
    val message = error ?: status ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) BossThemeColors.ErrorColor else BossThemeColors.SuccessColor)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = 11.sp,
            color = BossThemeColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryCards(aggregates: Aggregates) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile(
            label = "Today",
            primary = BudgetTracker.formatUsd(aggregates.today.totalCostUsd),
            secondary = "${aggregates.today.calls} calls - ${aggregates.today.totalTokens} tokens",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "This month",
            primary = BudgetTracker.formatUsd(aggregates.monthSpendUsd),
            secondary = "${aggregates.thisMonth.calls} calls - ${aggregates.monthTokens} tokens",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    primary: String,
    secondary: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.6f))
            .padding(8.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = primary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onBackground,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = secondary,
            fontSize = 9.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun BudgetSection(viewModel: LlmCostViewModel, aggregates: Aggregates) {
    var budgetInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Monthly budget",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.8f),
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (aggregates.budget > 0.0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${BudgetTracker.formatUsd(aggregates.monthSpendUsd)} / " +
                        BudgetTracker.formatUsd(aggregates.budget),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = BudgetTracker.formatPercent(aggregates.budgetProgressPercent),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        aggregates.budgetProgressPercent >= 1.0 -> BossThemeColors.ErrorColor
                        aggregates.budgetProgressPercent >= 0.8 -> Color(0xFFFFA726)
                        else -> BossThemeColors.SuccessColor
                    },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = aggregates.budgetProgressPercent.coerceIn(0.0, 1.0).toFloat(),
                onValueChange = { },
                valueRange = 0f..1f,
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(16.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = budgetInput,
                onValueChange = { newValue ->
                    budgetInput = newValue.filter { it.isDigit() || it == '.' || it == '-' }
                },
                placeholder = { Text("Set monthly budget (USD)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = MaterialTheme.colors.onBackground,
                    cursorColor = MaterialTheme.colors.primary,
                ),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Button(
                onClick = {
                    val parsed = budgetInput.trim().toDoubleOrNull()
                    if (parsed != null) {
                        scope.launch {
                            viewModel.setBudget(parsed)
                            budgetInput = ""
                        }
                    }
                },
                enabled = budgetInput.trim().isNotEmpty(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 4.dp,
                ),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
            ) {
                Text("Save", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BreakdownTables(aggregates: Aggregates) {
    Spacer(modifier = Modifier.height(4.dp))
    SectionHeader("By plugin")
    if (aggregates.byPlugin.isEmpty()) {
        EmptyHint("No calls recorded yet")
    } else {
        for (row in aggregates.byPlugin) {
            BreakdownRow(
                label = row.pluginId,
                primary = BudgetTracker.formatUsd(row.totalCostUsd),
                secondary = "${row.calls} calls - ${row.promptTokens + row.completionTokens} tokens",
            )
        }
    }
    Spacer(modifier = Modifier.height(6.dp))

    SectionHeader("By model")
    if (aggregates.byModel.isEmpty()) {
        EmptyHint("No calls recorded yet")
    } else {
        for (row in aggregates.byModel) {
            BreakdownRow(
                label = row.modelId,
                primary = BudgetTracker.formatUsd(row.totalCostUsd),
                secondary = "${row.calls} calls - ${row.totalTokens} tokens",
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun BreakdownRow(label: String, primary: String, secondary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = primary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = secondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun RecentCalls(calls: List<CostCall>) {
    Spacer(modifier = Modifier.height(6.dp))
    SectionHeader("Recent calls")
    if (calls.isEmpty()) {
        EmptyHint("No calls recorded yet")
    } else {
        for (call in calls) {
            RecentCallRow(call)
        }
    }
}

@Composable
private fun RecentCallRow(call: CostCall) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = call.modelId,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = BudgetTracker.formatUsd(call.totalCostUsd),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onBackground,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
