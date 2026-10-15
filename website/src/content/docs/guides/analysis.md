---
title: 'Analyze architecture'
description: 'Generate Tile architecture graphs and check Canvas bindings with optional build tooling.'
---

Turn composition into a dependency report, then use verification to find missing Canvas bindings and dangerous recursive Tile dependencies. Analysis is optional: the runtime works without it. The [analysis configuration reference](/reference/analysis-configuration/) covers the full DSL, rules, suppressions, enforcement, roots, tasks, reports, and supported project boundary.

## Install the plugin

Independent Analysis releases are not yet published. For 0.7.0, use the [released analysis guide](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/mosaic-gradle-plugin/README.md). For the 1.0 API described here, select an Analysis version from the [compatibility table](/reference/compatibility/) once available; `<analysis-version>` below is a placeholder, not a published coordinate.

Add Maven Central to plugin and dependency repositories. For a supported Kotlin/JVM application:

```kotlin title="build.gradle.kts"
import org.buildmosaic.gradle.MosaicAnalysisEnforcement
import org.buildmosaic.gradle.MosaicAnalysisRole

plugins {
  kotlin("jvm") version "2.4.20"
  id("org.buildmosaic.analysis") version "<analysis-version>"
}

repositories { mavenCentral() }

mosaicAnalysis {
  role = MosaicAnalysisRole.APPLICATION
  enforcement = MosaicAnalysisEnforcement.STANDARD
}
```

In `settings.gradle.kts`, include `mavenCentral()` in `pluginManagement.repositories` alongside `gradlePluginPortal()`. Add runtime dependencies separately; the analysis plugin does not install Mosaic core or apply Kotlin for you.

## Generate the graph

```bash
./gradlew mosaicGraph
```

Open `build/reports/mosaic-analysis/graph.md`. The Markdown contains Mermaid relationships for Tiles, MultiTiles, Canvas requirements, bindings, and selected dependency contracts. APPLICATION reports also focus on each analysis root and include analyzer findings.

![Generated order architecture: page to summary and logistics, with three branches sharing OrderTile and keyed products and pricing below line items.](/order-architecture.png)

This order graph is derived from real `mosaicGraph` output. It shows possible static dependencies. It does not show runtime order, duration, execution counts, cache occupancy, or exact MultiTile batches. Use [tracing](/guides/tracing/) for work that actually executed.

The graph overview makes no verification claim. Unknown and missing requirements remain visible without making graph generation fail; invalid roots, summaries, and conflicting declaration owners fail. You can commit a generated Markdown snapshot for a team's architecture discussion.

## Verify dependencies

```bash
./gradlew verifyMosaic
```

Verification also participates in `check`. Read `build/reports/mosaic-analysis/main.txt`, including warnings and root status.

| Role / enforcement     | Behavior                                                       |
| ---------------------- | -------------------------------------------------------------- |
| APPLICATION / STANDARD | Fail proven errors and ERROR policy rules; warn on uncertainty |
| APPLICATION / STRICT   | Fail proven errors, ERROR policy rules, and uncertainty        |
| LIBRARY / either       | Validate and export contracts; no application verification     |

A STANDARD pass with warnings remains `UNVERIFIED` on uncertain paths. It is not proof of every lookup. Malformed, partial, incompatible, or integrity-invalid summaries are artifact errors and fail in both enforcement modes.

## Configure recursion policy

Stable synchronous dependencies on unfinished results in the same Mosaic fail with `MOSAIC_CYCLIC_TILE_DEPENDENCY`. Recursive Tile and MultiTile structures also default to ERROR when that stronger proof is unavailable. For intentional changing-key recursion, use a local `@Suppress("MOSAIC_RECURSIVE_MULTITILE")` or configure the named rule:

```kotlin
mosaicAnalysis {
  rules {
    severity("MOSAIC_RECURSIVE_MULTITILE", org.buildmosaic.analysis.MosaicRuleSeverity.WARNING)
  }
}
```

STRICT preserves an explicit rule WARNING. Proven cyclic result dependencies cannot be reconfigured or suppressed. Read the [rules and suppression reference](/reference/analysis-configuration/#rules) for boundaries and complete examples.

## Choose entry contexts

With no explicit roots, APPLICATION discovers the outermost safe execution contexts from selected contracts. If automatic selection cannot establish a safe root, use callable IDs to select your intended entry contexts:

```kotlin
mosaicAnalysis {
  roots.add("app.entry()")
}
```

Explicit roots replace discovery exactly; they do not add to it. Reports explain selection and any concrete receiver. Unsupported lifecycle callbacks and unresolved virtual dispatch are not silently treated as verified.

A library exports contracts for consuming applications:

```kotlin
mosaicAnalysis {
  role = MosaicAnalysisRole.LIBRARY
}
```

Libraries omit roots. A library with configured roots fails configuration.

## Build behavior

Analysis runs during normal `main` Kotlin compilation. Your JAR carries the resulting complete contract summary, so consuming applications can inspect selected dependency contracts without running your code. Verification can refresh when dependency metadata changes even if your application sources do not recompile.

Use the [configuration reference](/reference/analysis-configuration/) for supported boundaries and troubleshooting, including [tasks and reports](/reference/analysis-configuration/#tasks-and-reports).
