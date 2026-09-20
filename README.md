# boss-plugin-llm-cost-tracker

A BOSS dynamic plugin that aggregates every recorded model call by plugin, by
model, by day, and across the running month, with monthly budget alerts.

No plugin currently tracks per-call token usage and cost across the host; each
plugin that calls the model records its own usage and nothing aggregates it.
This plugin is the first cost tracker for BOSS: it consumes the host's
`LlmProvider` pricing source where one is published, falls back to a baked
rate table for the public model ids a fresh install is likely to see, and
records calls through `cost_record` (MCP) plus an inline sidebar panel that
shows today's spend, this month's spend against a user-set budget, and a
per-plugin / per-model breakdown.

## What you get

- Sidebar panel at `left_bottom` (priority 90) titled **LLM Cost**
- Today / This month stat cards
- Monthly budget editor with live progress bar (green under 80%, amber 80% to
  100%, red over)
- Per-plugin and per-model breakdowns sorted by total cost
- Recent calls list (last 50)
- CSV / JSON export buttons
- MCP tools surfaced on the `boss` MCP server:
  - `cost_record(callJson)`
  - `cost_summary(window)`
  - `cost_by_plugin(window)`
  - `cost_by_model(window)`
  - `cost_recent(limit)`
  - `cost_set_budget(monthlyUsd)`
  - `cost_get_budget()`
  - `cost_export(format)`

## Static pricing fallback

When the host has no `LlmModelPricingAPI` provider registered, the plugin
falls back to a baked table so the install is useful out of the box. Rates
are USD per one million tokens, matching the host's `AiModelPricing`
convention.

| Model | Input USD / 1M | Output USD / 1M |
| --- | --- | --- |
| `gpt-4o` | 2.50 | 10.00 |
| `gpt-4o-mini` | 0.15 | 0.60 |
| `gpt-4.1` | 2.00 | 8.00 |
| `gpt-4.1-mini` | 0.40 | 1.60 |
| `gpt-4-turbo` | 10.00 | 30.00 |
| `o1` | 15.00 | 60.00 |
| `o1-mini` | 3.00 | 12.00 |
| `o3` | 10.00 | 40.00 |
| `o3-mini` | 1.10 | 4.40 |
| `claude-3.5-sonnet` | 3.00 | 15.00 |
| `claude-3.5-haiku` | 0.80 | 4.00 |
| `claude-3-opus` | 15.00 | 75.00 |
| `claude-3-haiku` | 0.25 | 1.25 |
| `claude-sonnet-4` | 3.00 | 15.00 |
| `claude-opus-4` | 15.00 | 75.00 |
| `gemini-1.5-pro` | 1.25 | 5.00 |
| `gemini-1.5-flash` | 0.075 | 0.30 |
| `gemini-2.0-flash` | 0.10 | 0.40 |
| `llama-3-70b` | 0.59 | 0.79 |
| `llama-3.1-70b` | 0.59 | 0.79 |
| `llama-3.1-405b` | 2.50 | 2.50 |

A call against a model not in this table and not in the host's pricing
provider records `totalCostUsd = 0.0` rather than throwing - the panel still
shows the call, the spend it contributes to the running total is zero, and
the user can see in `Recent calls` which calls priced to zero.

## Install

1. Download `boss-plugin-llm-cost-tracker-0.1.0.jar` from the GitHub Releases
   page of this repository.
2. Open BOSS and go to the Toolbox (`Settings > Plugins > Install from file`).
3. Pick the jar and enable it.
4. The plugin appears in the left sidebar under **LLM Cost** and contributes
   `cost_*` tools to the `boss` MCP server for any in-terminal agent.

## Budget alerts

Set a monthly budget in the panel (or through `cost_set_budget`). The plugin
emits two alerts at sticky thresholds per calendar month:

- 80% reached - a synthetic `budget_alert:warn_80` call is appended to the
  record and a warning toast is shown if the host supplies a
  `NotificationProvider`.
- 100% exceeded - a synthetic `budget_alert:exceeded_100` call is appended
  and an error toast is shown.

The synthetic alert contributes zero dollars to the running total, so it
warns about spend without inflating the spend it warns about. Alerts are
suppressed once per month per threshold - a budget lowered mid-month can
re-cross and refire.

## Compatibility

- BOSS plugin API `1.0.92` or newer (the `LlmModelPricingAPI` interface
  shipped in 1.0.92).
- Java 17 runtime.
- `PluginStorageProvider` and `NotificationProvider` are optional - the
  plugin still loads and reports through MCP if either is missing; the
  sidebar panel renders without budget toasts.

## License

Distributed under the same terms as the host project. See the upstream
repository for license details.
