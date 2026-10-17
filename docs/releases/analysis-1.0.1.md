# Mosaic Analysis 1.0.1

Released October 16, 2026.

Analysis 1.0.1 improves optional Canvas verification through the
`org.buildmosaic.analysis` Gradle plugin.

## Verification fixes

Ordinary scalar and nullable string interpolations no longer cause false
`UNVERIFIED` results. Mosaic lookups inside interpolations are still analyzed.
Potentially effectful object conversions, including user-defined `toString()`,
remain conservative.

`@Suppress("MOSAIC_UNVERIFIED")` and existing named-rule suppressions remove
applicable findings completely from Analysis results, text reports, and graphs.
Suppressions apply to their declaration scopes; an unrelated caller cannot hide
a callee's findings. Proven missing dependencies and non-suppressible cycles
remain errors. See the [suppression reference](https://BuildMosaic.org/reference/analysis-configuration/#kotlin-suppressions).

## Gradle compatibility

The plugin now declares configuration-cache compatibility, verified with
published-artifact consumers. See the [analysis guide](https://BuildMosaic.org/guides/analysis/)
for installation and [compatibility](https://BuildMosaic.org/reference/compatibility/)
for supported toolchains.
