# Mosaic Gradle analysis plugin

`org.buildmosaic.analysis` integrates optional contract extraction, verification,
and architecture graphs into Kotlin/JVM builds. It does not apply Kotlin or add
Mosaic runtime dependencies. Runtime and Analysis versions are independent;
compiler artifacts match the Analysis plugin version and remain outside the BOM.

This checkout describes the upcoming 1.0 tooling. For published 0.7.0 installation
and behavior, use the [released guide](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/mosaic-gradle-plugin/README.md).
Use the [consumer analysis guide](https://BuildMosaic.org/guides/analysis/) for
installation and a walkthrough. The [configuration reference](https://BuildMosaic.org/reference/analysis-configuration/)
owns the DSL, roots, rules, suppressions, task/report paths, and supported project
boundary; [compatibility](https://BuildMosaic.org/reference/compatibility/) owns
supported Runtime, Analysis, and compiler/KGP combinations.

## Application and library use

An application defaults to `APPLICATION` / `STANDARD`. It discovers safe entry
contexts unless you select explicit callable IDs:

```kotlin
mosaicAnalysis {
  roots.add("app.entry()")
}
```

A library validates and exports contracts without selecting application roots:

```kotlin
import org.buildmosaic.gradle.MosaicAnalysisRole

mosaicAnalysis {
  role = MosaicAnalysisRole.LIBRARY
}
```

Both roles package complete summaries for consumers. STANDARD warns on uncertain
paths without turning them into `VERIFIED`; STRICT fails uncertainty. Proven
errors and invalid metadata fail under either mode. For intentional recursion,
use the reference's named rules and source-local suppressions rather than
weakening the entire verification policy.

Run `./gradlew verifyMosaic` to inspect dependency findings or `./gradlew mosaicGraph`
for Mermaid architecture documentation. Graphs show static dependencies and
unknown boundaries, not execution order, timing, or batch partitions.

## Build ownership

`MosaicAnalysisPlugin` installs the compiler subplugin into normal `main` Kotlin
compilation and chooses a bounded compatible introspector. The compiler owns
source extraction; `ExtractMosaicTask` assembles current-source shards, including
an explicit empty summary when Kotlin reports no sources. There is no second
production compiler invocation.

`MosaicSummaryTransform` selects packaged dependency metadata without decoding
it. `MosaicDependencyReader` owns decoding and validation. Verification and graph
tasks resolve selected roots and report the same semantic findings; verification
also enforces policy. Dependency metadata changes can invalidate verification
without recompiling unchanged application sources. The [invalidation matrix](src/test/INVALIDATION_MATRIX.md)
describes the tested incremental and cache boundaries.

[Analysis core](../mosaic-analysis-core/README.md) owns the metadata contract,
evaluator semantics, and explicit Core semantic admission. The
[compiler module](../mosaic-compiler-plugin/README.md) owns IR extraction fidelity.
An unrelated JAR without metadata is harmless; a reached dependency with missing
metadata remains unknown. Present but incompatible metadata fails explicitly;
regenerate summaries with compatible tooling. Release numbers alone do not
establish metadata or Runtime semantic compatibility.
