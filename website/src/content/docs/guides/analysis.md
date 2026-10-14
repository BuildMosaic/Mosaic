---
title: 'Analyze architecture'
description: 'Generate Tile architecture graphs and check Canvas bindings with optional build tooling.'
---

Turn composition into a dependency report, then use verification to find missing Canvas bindings and dangerous recursive Tile dependencies. Analysis is optional: the runtime works without it. The [analysis configuration reference](/reference/analysis-configuration/) covers the full DSL, rules, suppressions, enforcement, roots, tasks, reports, and supported project boundary.

## Install the plugin

Add Maven Central to plugin and dependency repositories. For a supported Kotlin/JVM application:

```kotlin title="build.gradle.kts"
import org.buildmosaic.gradle.MosaicAnalysisEnforcement
import org.buildmosaic.gradle.MosaicAnalysisRole

plugins {
  kotlin("jvm") version "2.4.20"
  id("org.buildmosaic.analysis") version "0.7.0"
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

## What runs during the build

Analysis runs during normal `main` Kotlin compilation without another compiler process. For incremental builds, the compiler stores source-relative internal shards; `extractMosaicMain` assembles current-source shards into one complete summary. With no Kotlin sources, it produces an explicit empty complete summary. The JAR packages that summary at `META-INF/mosaic-analysis/v1/summary.json`.

Dependency summary changes can rerun verification independently of unchanged source compilation. Incremental, clean, and cache-restored builds must yield the same summary and verification result.

Use the [analysis configuration reference](/reference/analysis-configuration/) for configuration and troubleshooting, including [tasks and reports](/reference/analysis-configuration/#tasks-and-reports). For implementation details, the [compiler guide](https://github.com/BuildMosaic/Mosaic/blob/main/mosaic-compiler-plugin/README.md) describes extraction fidelity, and the [analysis-core guide](https://github.com/BuildMosaic/Mosaic/blob/main/mosaic-analysis-core/README.md) describes evaluation and metadata semantics. These provisional build-tooling interfaces are separate from the runtime API and BOM.
