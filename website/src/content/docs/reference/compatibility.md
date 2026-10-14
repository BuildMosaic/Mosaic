---
title: 'Compatibility'
description: 'Runtime, test, tracing, and optional analysis requirements.'
---

Runtime libraries and optional analysis have different requirements.

## Runtime libraries

| Area                          | Supported or tested requirement                                    |
| ----------------------------- | ------------------------------------------------------------------ |
| Runtime platform              | Kotlin/JVM; Java 17 or later                                       |
| Runtime consumer compiler     | Kotlin 2.3.0 or later; 2.3.0 consumer coverage in repository tests |
| Mosaic build                  | Kotlin compiler/Gradle plugin 2.4.20; language/API level 2.4       |
| Kotlin runtime dependencies   | stdlib and kotlin-test 2.4.20                                      |
| Coroutines                    | core/test 1.11.0                                                   |
| Repository build and examples | JDK 21; Gradle wrapper 8.14.4                                      |
| OpenTelemetry adapter         | API 1.66.0; application supplies SDK or agent                      |

The BOM aligns `mosaic-core`, `mosaic-test`, and `mosaic-opentelemetry` at one Mosaic version. It does not include analysis tooling. Runtime use needs no analysis plugin or registration processor.

## Optional analysis

Analysis supports exactly **Kotlin compiler and Gradle plugin 2.4.20**, with the tested pure Kotlin/JVM `main` source layout under `src/main/kotlin`.

The plugin rejects nonstandard/generated Kotlin source paths, scripts, mixed Java/Kotlin, friend paths, other compiler plugins, plugin options, opt-ins, free compiler arguments, progressive mode, nondefault JVM interface mode, no-JDK compilation, and KSP. Android, multiplatform, test sources, framework lifecycle callbacks, and arbitrary virtual dispatch are unsupported.

The plugin validates the Java toolchain against Kotlin's toolchain. Regenerate dependency summaries produced with incompatible toolchains. Metadata compatibility is determined by its header, not by the resource directory name.

[Configure analysis](/guides/analysis/) and read the full [supported project boundary](/reference/analysis-configuration/#supported-project-boundary). Unknown paths stay visible; a STANDARD warning pass is not proof that every lookup is safe.

## Release migration

When upgrading from 0.6.0 to **0.7.0**, move the complete request handler invocation inside `Canvas.withMosaic`. `Canvas.create()` remains available with a WARNING deprecation. Register externally owned services and request values with `instance(...)`; reserve `single { ... }` for values Canvas constructs and owns. Never retain a Mosaic between requests.

MultiTile failures now settle per key or physical provider invocation, retaining successful siblings. Returned Deferreds are shared and Mosaic-owned: cancel your own wait rather than the Deferred. These runtime behavior changes require application testing even when source still compiles.

The runtime additions and deprecation retain the inspected Kotlin-public core method signatures. The internal MultiTile provider representation and its mangled JVM getter changed, so Java/reflection access to internals can break. This is not a general source or binary compatibility guarantee for 0.x. Recompile and test consumers. Optional analysis has incompatible format/semantic metadata: rebuild dependency summaries with matching 0.7 tooling and Kotlin/compiler plugin 2.4.20. Runtime use remains independent of analysis.

Read the [0.7.0 migration and compatibility notes](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/docs/releases/0.7.0.md#compatibility). For earlier upgrades, retain the [0.6.0 Canvas construction migration](https://github.com/BuildMosaic/Mosaic/blob/0.6.0/docs/releases/0.6.0.md#compatibility-and-migration) and the [release history](/reference/releases/).
