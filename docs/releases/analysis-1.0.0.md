# Mosaic Analysis 1.0.0

Release candidate; not yet published. No publication date has been assigned.

Analysis is optional build tooling for Canvas verification and architecture
graphs. It releases independently of Runtime. The `org.buildmosaic.analysis`
Gradle plugin and its compiler-profile artifacts share the Analysis version;
they are outside the Runtime BOM and do not install Runtime dependencies.

## Upgrade from 0.7.0

Upgrade Runtime using its [migration notes](../../docs/releases/runtime-1.0.0.md). After publication,
select Analysis 1.0.0 only with an exact certified Runtime and Kotlin compiler/KGP
combination from the [compatibility reference](https://BuildMosaic.org/reference/compatibility/).
The plugin automatically selects a bounded compatible compiler integration;
applications do not select compiler-profile artifacts themselves. Compiler ABI
admission permits compatible stable patches but does not certify untested versions.
The rolling 18-month Kotlin support policy is unchanged.

Rebuild analyzed libraries and applications with compatible Analysis tooling.
**Contract version 6** replaces 0.7.0 metadata; older summaries must be regenerated.
The complete `main` summary records producer Analysis/compiler versions as
provenance, plus a canonical payload hash. Release-number equality is not the
metadata compatibility rule.

Core separately declares semantic revision 1 in its JAR manifest. Analysis checks
the selected Core revision and all requirements retained in dependency summaries;
Analysis 1.0 understands revision 1. Unsupported revisions fail explicitly with
remediation rather than generating trusted results. Decoding metadata does not
certify Runtime, compiler, or Gradle interoperability.

## Guarantees and limits

Analysis configuration, compiler integration, metadata, diagnostics, and reports
remain explicitly provisional in 1.0, separate from stable Runtime API guarantees.
Breaking tooling changes include migration or regeneration guidance.

The conservative verification model is unchanged: missing metadata on a reached
dependency remains `UNVERIFIED`; present malformed, incomplete, incompatible, or
integrity-invalid metadata fails. STANDARD warns on uncertainty; STRICT also fails
it. Both fail proven errors, and proven Tile cycles cannot be suppressed. Unsupported
paths and Runtime semantics never silently become `VERIFIED`.

Analysis still runs inside normal `main` Kotlin compilation, with no separate
production compiler process. Consult the [installation guide](../../mosaic-gradle-plugin/README.md)
and [supported project boundary](https://BuildMosaic.org/reference/analysis-configuration/#supported-project-boundary)
for compiler options, source layouts, plugins, and platforms. Historical
[0.7.0 instructions](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/mosaic-gradle-plugin/README.md)
remain available.
