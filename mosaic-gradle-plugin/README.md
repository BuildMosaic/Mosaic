# Mosaic Gradle analysis plugin

## Installation

`org.buildmosaic.analysis` supports pure Kotlin/JVM
`main` sources under `src/main/kotlin` with Kotlin Gradle plugin and compiler
**2.4.20 only**.
It is build tooling: it does not apply Kotlin or add Mosaic application runtime
dependencies. Add Maven Central to plugin and dependency repositories. Install
version 0.7.0 with:

```kotlin
import org.buildmosaic.gradle.MosaicAnalysisEnforcement
import org.buildmosaic.gradle.MosaicAnalysisRole

plugins {
  kotlin("jvm") version "2.4.20"
  id("org.buildmosaic.analysis") version "0.7.0"
}

mosaicAnalysis {
  role = MosaicAnalysisRole.APPLICATION
  enforcement = MosaicAnalysisEnforcement.STANDARD
}
```

See the [analysis guide](https://BuildMosaic.org/guides/analysis/) for a walkthrough
and the canonical [analysis configuration reference](https://BuildMosaic.org/reference/analysis-configuration/)
for every DSL option, rule, suppression scope, task/report, and supported boundary.

Configurable recursion rules use a dedicated section:

```kotlin
mosaicAnalysis {
  rules {
    severity("MOSAIC_RECURSIVE_MULTITILE", org.buildmosaic.analysis.MosaicRuleSeverity.WARNING)
  }
}
```

The central rule registry defaults both recursive Tile/MultiTile policies to
ERROR. Named WARNING/OFF settings remain independent of STANDARD/STRICT.
Proven cyclic Tile dependencies are hard correctness errors and cannot be
configured or suppressed. Source suppressions are preserved in binary contracts
and remain visible in full reports.

## Roles and enforcement

`role` and `enforcement` are independent typed settings. The defaults are
`APPLICATION` and `STANDARD`. The normal application setup above leaves `roots`
empty, so analysis discovers the outermost safe Mosaic execution contexts from
selected contracts. Add explicit callable IDs when an application needs to
choose its own entry contexts; these entries replace discovery exactly:

```kotlin
mosaicAnalysis { roots.add("app.entry()") }
```

Libraries omit roots and export the same complete summary in their JAR:

```kotlin
mosaicAnalysis {
  role = MosaicAnalysisRole.LIBRARY
}
```

The Gradle plugin uses Kotlin 2.4.20’s
`KotlinCompilerPluginSupportPlugin` API to resolve the same-version
`mosaic-compiler-plugin` artifact for the `main` Kotlin/JVM
compilation. No compiler JAR path or application dependency is needed. The
compiler artifact is self-contained; its publication does not add a second
analysis-core or serialization runtime to the compiler plugin classpath.

Applications also export contracts. A library with roots is contradictory and
fails with a configuration error.

## Build and task architecture

`compileKotlin` runs Mosaic’s IR extractor inside the normal Kotlin/JVM `main`
compilation. There is no second Kotlin compiler process in supported production
builds. Kotlin decides which sources to recompile. The extractor writes one
internal shard for each file Kotlin presents, including a shard when a file no
longer contributes Mosaic declarations. `compileKotlin` declares the complete
shard directory as an output, so a build-cache restore supplies all shards
required by a clean workspace. After writing affected shards, the compiler
plugin removes shards for sources no longer under `src/main/kotlin`. Shard
storage is versioned internally and is not dependency metadata or a public
compatibility format.

`extractMosaicMain` assembles and validates a complete summary without running a
compiler. It uses the current `src/main/kotlin` source manifest, so stale shards
from deleted or renamed files cannot enter the result or its Gradle input
fingerprint. Only paths for current source shards are declared as task inputs.
If all Kotlin sources are removed, it emits an explicit empty complete summary
without invoking Mosaic’s compiler plugin, even if `compileKotlin` reports
`NO_SOURCE` and leaves physical shards behind. A missing current shard or
malformed shard fails assembly.
`jar` embeds the complete summary at
`META-INF/mosaic-analysis/v1/summary.json`. `verifyMosaicMain` reads that local
summary and selected dependency summaries, writes
`build/reports/mosaic-analysis/main.txt`, and is aggregated by `verifyMosaic`
and `check`.

## Dependency documentation

Run `./gradlew mosaicGraph` to write `build/reports/mosaic-analysis/graph.md`.
The Markdown contains Mermaid diagrams for local Tiles and MultiTiles, Canvas
bindings and requirements, their Mosaic relationships, and relevant selected
dependency contracts. For example, if `ResponseTile` composes `ProfileTile`,
which requests `UserRepository`, the diagram links both Tiles and shows the
Canvas requirement on `ProfileTile`.

The module overview makes no verification claim. APPLICATION graphs include a
focused diagram and analyzer findings for each discovered or explicit root,
including uncertainty and the selected receiver; LIBRARY graphs show the
overview only. Missing or unverified
requirements appear in the report without failing graph generation; invalid
roots, summaries, and conflicting selected owners fail. The diagrams
describe possible static relationships, not runtime traces, execution order,
timing, call counts, or exact MultiTile batches.

To retain a snapshot for team documentation, copy the generated Markdown into
your documentation directory and commit that copy. Mermaid-capable Markdown
viewers can display its diagrams. The task reuses the assembled summary and
selected dependency summaries, so it launches no separate compiler process.

## Incremental and cache behavior

Dependency contract invalidation is separate from source compilation.
Verification reads the raw summary resource copied by a cacheable per-JAR
artifact transform. A dependency Mosaic contract change reruns verification
without forcing unchanged application source extraction. A JAR without the
resource produces no summary; a present malformed resource fails verification.
Kotlin’s own incremental compilation handles public constants, typealiases,
inline bodies, and other source-resolution changes. Mosaic does no additional
source-resolution fingerprinting or separate K2 compilation.

In APPLICATION mode verification checks discovered or configured roots. In LIBRARY mode it
validates and exports the complete local summary as `EXPORT_ONLY`. Both roles
package the same summary resource.

Automatic discovery follows selected-contract calls to `compose` execution and
selects the outermost safely established selected-contract boundary. A template
method in local code or a library or framework dependency that forwards to an
application override is specialized for each concrete application receiver.
Unrelated lookups and unknown declarations do not create roots by themselves.
Constructing a Canvas without a Mosaic execution path does not create one. If no
safe root covers the Mosaic execution found, verification and graph
generation fail with a root selection error and guidance to configure an
explicit root.
Configured roots replace automatic selection exactly. Reports show how roots
were selected and any concrete receiver. LIBRARY with no roots passes ordinary
`check`/`build` after extraction and local summary validation. Explicit
`verifyMosaicMain` and `verifyMosaic` use the same role-specific behavior.

| Role | Enforcement | Result |
| --- | --- | --- |
| APPLICATION | STANDARD | Proven correctness errors and ERROR rules fail; unverifiable boundaries pass with visible warnings and `UNVERIFIED` root status. |
| APPLICATION | STRICT | Proven correctness errors, ERROR rules, and unverifiable boundaries fail. |
| LIBRARY | STANDARD or STRICT | Validate/export the complete local summary; application verification is not requested. |

STANDARD maps to the analysis kernel's `DEFAULT` policy. An uncertainty warning
pass preserves `UNVERIFIED` root status. Named policy warnings retain their
configured severity under either enforcement mode. Changing role, enforcement,
roots, or named rule severities invalidates verification.

An unrelated JAR without Mosaic metadata is harmless. If an application reaches
a declaration whose dependency artifact has no summary, that boundary remains
unknown: STANDARD warns and STRICT fails. A summary that is present but malformed,
partial, incompatible, or integrity-invalid is an artifact error and fails in
both modes. Invalid local summaries also fail. Conflicting selected declaration
owners fail. These configuration and artifact errors are never converted into
STANDARD warnings.
Selected binary constructors use producer constructor contracts, including
capability-bearing initialization marked unknown. If the referenced constructor
summary is absent, verification is unknown. Referenced stored-property reads
likewise retain initialization work. This is not a general model of JVM class
initialization or exception safety. Used user defaults with unavailable binary
expressions and Mosaic extension-helper calls remain unknown. Dependency summaries
must satisfy the [metadata compatibility contract](../mosaic-analysis-core/README.md#binary-metadata);
regenerate incompatible summaries with the supported Mosaic analysis toolchain.

## Supported project boundary

The canonical [supported project boundary](https://BuildMosaic.org/reference/analysis-configuration/#supported-project-boundary)
lists all Kotlin/JVM, source-layout, compiler-option, plugin, and platform
restrictions. The DSL, format-4 metadata, and report format are provisional.
Analysis tooling is not included in the runtime BOM.

## Publication

The release publishes `org.buildmosaic:mosaic-compiler-plugin` and
`org.buildmosaic:mosaic-gradle-plugin` to Maven Central at the same Mosaic
version. Gradle also generates the `org.buildmosaic.analysis` plugin marker.
Both plugins bundle the internal analysis-core implementation they need;
`mosaic-analysis-core` is not published separately. The compiler plugin remains
a separate artifact loaded through Kotlin Gradle Plugin's standard
`getPluginArtifact()` resolution. These analysis artifacts are not application
dependencies or BOM entries. The root `release` task publishes the six Mosaic
modules to Maven Central, then submits the Gradle plugin through the Plugin
Portal. Local installation tests use a temporary Maven repository.
