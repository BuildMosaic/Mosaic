# Mosaic Analysis 1.0.2

Released November 28, 2026.

Analysis 1.0.2 improves source extraction and gives applications a useful
architecture overview when automatic verification cannot select a safe root.
Mosaic Runtime is unchanged.

## Analyze the Kotlin sources your build actually uses

Analysis follows the configured main Kotlin compilation, including generated
Kotlin sources. It no longer rejects ordinary compiler settings and friend
paths just for being present. Mixed Kotlin/Java projects can analyze their
Kotlin sources; Analysis does not verify Java bodies.

Analysis also recognizes supported inherited accessors and direct same-file
helpers on a known Mosaic receiver. Unsupported dispatch and callback
boundaries remain unverified.

## Generate a graph without claiming a proof

`mosaicGraph` can produce an application-wide structural overview even when
automatic root discovery finds no safe execution context. That result is not
verification. Framework-managed request entry points and dependency injection
are not inferred; the Runtime integrations are unaffected.

See the [analysis guide](https://BuildMosaic.org/guides/analysis/) and the
[compatibility reference](https://BuildMosaic.org/reference/compatibility/)
for setup and supported toolchains.
