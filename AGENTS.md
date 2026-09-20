# AGENTS.md

This file provides guidance to coding agents working with this repository.

## Project overview

`boss-plugin-llm-cost-tracker` is a BOSS dynamic plugin (panel + MCP tools)
that aggregates per-call model usage into per-plugin, per-model, and
per-day totals with monthly budget alerts. It is the first cost-tracker
plugin for BOSS and intentionally fills a gap nothing else covers.

The plugin is built against `boss-plugin-api` 1.0.92 or newer, which is the
release that introduced the `LlmModelPricingAPI` interface the pricing path
casts to. Older hosts cannot serve pricing and the static fallback table
takes over.

## Build

```bash
./gradlew clean buildPluginJar -x test --no-daemon
```

The output is `build/libs/boss-plugin-llm-cost-tracker-0.1.0.jar`. The
`Tests` GitHub Actions workflow runs `./gradlew build`, which depends on
`buildPluginJar`, so a green CI run also guarantees a publishable jar.

The local Gradle build uses the sibling `boss-plugin-api` jar under
`../boss-plugin-api/build/libs/boss-plugin-api-1.0.93.jar`. CI sets `CI=true`
and switches to a downloaded jar under `build/downloaded-deps/boss-plugin-api.jar`
to mirror the release flow.

## Module structure

All code lives under `src/main/kotlin/ai/rever/boss/plugin/dynamic/llmcost/`:

| File | Purpose |
| --- | --- |
| `LlmCostDynamicPlugin.kt` | `DynamicPlugin` entry point - register the panel and the MCP tools. |
| `LlmCostInfo.kt` | The panel's `PanelInfo` (id, slot, icon). |
| `LlmCostComponent.kt` | The Decompose component - delegates to the Composable. |
| `LlmCostViewModel.kt` | State + write path; aggregates computed from `CostStore`. |
| `LlmCostContent.kt` | The Compose UI for the sidebar surface. |
| `CostCall.kt` | Data model - `CostCall`, `CostByPluginRow`, `CostByModelRow`, `CostSummary`, `CostWindow`. |
| `CostStore.kt` | Persistent storage via `PluginStorageProvider`. |
| `Pricing.kt` | USD lookup - host `LlmModelPricingAPI` then static fallback. |
| `BudgetTracker.kt` | In-memory monthly-spend monitor that emits alert calls. |
| `CsvExporter.kt` | Stable-column CSV serialiser. |
| `LlmCostMcpTools.kt` | The eight `cost_*` MCP tool definitions. |

## Bounded everywhere

The plugin refuses unbounded input. The hard caps are:

| Constant | Value | Where |
| --- | --- | --- |
| `CostStore.MAX_CALLS` | 50,000 | oldest evicted on append |
| `CostStore.MAX_BUDGET_USD` | 1,000,000 | refused above |
| `LlmCostViewModel.MAX_MODEL_ID_LENGTH` | 128 | `take(128)` on input |
| `LlmCostViewModel.MAX_PLUGIN_ID_LENGTH` | 128 | `take(128)` on input |
| `LlmCostViewModel.MAX_CALL_NAME_LENGTH` | 256 | `take(256)` on input |
| `LlmCostViewModel.MAX_TOKEN_COUNT` | 1,000,000,000 | `coerceAtMost` on input |

Refusal returns `CostRecordResult.Invalid(reason)` from `recordCall`; the
caller (MCP tool) surfaces it as `McpToolResult(... isError = true)`.

## Pricing resolution order

`Pricing.priceCall(modelId, promptTokens, completionTokens)` runs:

1. Cast `PluginContext.llmProvider` to `LlmModelPricingAPI`. If present,
   probe candidate provider ids (`anthropic`, `openai`, `google`, `xai`,
   `moonshot`, `together`) and return the first non-null card.
2. Fall back to `Pricing.DEFAULT_FALLBACK` keyed by lowercased model id.
3. Return a zero-cost `Resolved` with `Source.UNKNOWN`. The recorder still
   accepts the call - the user sees it in the panel under "priced 0.00"
   rather than the call being refused.

The recorded `totalCostUsd` is what the budget sees; the `source` is
returned in the `CostRecordResult.Ok` for the MCP client to surface.

## Budget alerting

`BudgetTracker.evaluate` is invoked under `LlmCostViewModel.recordCall`'s
mutex after every append. It looks at the current calendar month's spend
(calendar-month boundaries, not a sliding 30-day window), and emits up to
two synthetic `CostCall`s per month when crossing 80% and 100% of the
configured budget. The synthetic calls:

- Have `totalCostUsd = 0.0` so they don't inflate the running total they
  warn about.
- Have `callName = "budget_alert:<label>:<percent>:<spent>/<budget>"` so
  the `Recent calls` list shows them as budget events rather than model
  calls.
- Are sticky per calendar month: once an alert fires for a label it does
  not fire again until the month rolls over.
- Are tagged `callingPluginId = "ai.rever.boss.plugin.dynamic.llmcost"` so
  the breakdown table attributes them to this plugin.

Lowering the budget mid-month can re-cross a threshold and refire the
alert. Setting `monthlyUsd = 0` disables alerting.

## Storage

The plugin uses `PluginStorageProvider` with two keys:

| Key | Type | Contents |
| --- | --- | --- |
| `calls.v1` | JSON | `List<CostCall>`, oldest at index 0, capped at `MAX_CALLS`. |
| `monthly_budget_usd` | String | Double formatted as a string for portability. |

The list mirror in memory is the source of truth for the UI; persistence
runs after each append so the in-memory view always reflects the change
even when a disk write fails. The mutex serialises appends against budget
evaluation so a flurry of MCP calls cannot interleave a write and a budget
read.

## MCP tools

All eight tools are registered through `context.registerMcpToolProvider`
inside `LlmCostDynamicPlugin.register` and unregister automatically when
the plugin is disabled or unloaded. The handler bodies funnel through the
shared `LlmCostViewModel`; there is one write path so the panel, the
budget alerts, and the MCP surface read the same record.

`cost_summary` / `cost_by_plugin` / `cost_by_model` accept a `window`
argument that is one of `today`, `this_week`, `this_month`,
`last_30_days` (unknown values fall through to `last_30_days`). The
breakdown rows are filtered to the window by re-walking the source calls
in `windowCutoff(window)`; the aggregate summary uses the precomputed
`Aggregates` view.

## Workflow rules

- **Never** write the jar into `build/libs/` by hand; let `buildPluginJar`
  emit it. The jar name embeds the Gradle `version` property.
- **Never** edit `plugin.json` by hand to bump `version`; the
  `processResources` task rewrites the version from `$version`. Same for
  the release workflow, which dispatches on a new tag.
- **Never** raise `MAX_CALLS` or `MAX_BUDGET_USD` above the values in
  this file without first deciding what to do with the in-memory copy the
  UI holds - a larger cap is a larger working set.
- **Never** add a synthetic alert call that carries non-zero
  `totalCostUsd`. The alert is supposed to warn about spend, not contribute
  to it.

## Initial release notes

Version 0.1.0 ships the first cost-tracker panel and MCP surface for BOSS.
