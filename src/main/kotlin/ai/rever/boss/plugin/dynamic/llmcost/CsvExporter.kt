package ai.rever.boss.plugin.dynamic.llmcost

/**
 * CSV serialiser for [CostCall] records.
 *
 * Columns are stable so external consumers (spreadsheets, BI tools, awk
 * pipelines) can rely on the position: id, timestamp, model, prompt tokens,
 * completion tokens, total tokens, total cost USD, calling plugin, call name.
 * Header is always emitted - empty input still produces a header line.
 *
 * Values are quoted only when they contain the separator, a quote, a CR, or
 * an LF; bare numerics and unbraced ids are emitted unquoted so the common
 * case stays human-readable.
 */
object CsvExporter {

    fun toCsv(calls: List<CostCall>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (call in calls) {
            sb.append(quote(call.id)).append(',')
            sb.append(call.timestamp).append(',')
            sb.append(quote(call.modelId)).append(',')
            sb.append(call.promptTokens).append(',')
            sb.append(call.completionTokens).append(',')
            sb.append(call.totalTokens).append(',')
            sb.append(formatUsd(call.totalCostUsd)).append(',')
            sb.append(quote(call.callingPluginId ?: "")).append(',')
            sb.append(quote(call.callName ?: ""))
            sb.append('\n')
        }
        return sb.toString()
    }

    private const val HEADER = "id,timestamp,model,prompt_tokens,completion_tokens,total_tokens,total_cost_usd,calling_plugin,call_name"
    private const val SEPARATOR = ","
    private const val QUOTE = "\""

    private fun quote(value: String): String {
        if (value.isEmpty()) return value
        val needsQuoting = value.contains(SEPARATOR) || value.contains(QUOTE) ||
            value.contains('\n') || value.contains('\r')
        if (!needsQuoting) return value
        val escaped = value.replace(QUOTE, "$QUOTE$QUOTE")
        return "$QUOTE$escaped$QUOTE"
    }

    private fun formatUsd(value: Double): String = "%.6f".format(value)
}
