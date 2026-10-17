# Changelog

Significant user-facing changes to Mosaic are documented here. Earlier versions
predate this maintained changelog; their release history is not reconstructed here.

## [runtime-1.1.0] - 2026-10-17

### Added

- `CanvasDependencyResolver` and `CanvasBuilder.dependencyResolver(...)` allow
  external dependency lookup for unchanged Tiles and Canvas constructors.
  Explicit local and parent bindings take precedence; child layers inherit the
  resolver. Canvas does not cache external results or misses.
- Resolver lookups borrow values from their container. Existing `provide`
  ownership remains unchanged; use `instance` for borrowed bindings and aliases.
  Missing dependencies remain distinct from resolver failures.

### Compatibility

- Runtime 1.0 source and JVM binary compatibility are preserved. Framework
  adapters are not included. Analysis remains independently versioned and
  conservative about dynamic resolution.

## [analysis-1.0.1] - 2026-10-16

### Fixed

- Ordinary scalar and nullable string interpolations no longer produce false
  `UNVERIFIED` results. Mosaic lookups inside interpolations remain analyzed;
  potentially effectful object conversions remain conservative.
- `@Suppress("MOSAIC_UNVERIFIED")` and existing named-rule suppressions remove
  applicable findings from Analysis results, reports, and graphs. Proven missing
  dependencies and non-suppressible cycles cannot be hidden.

### Changed

- The `org.buildmosaic.analysis` Gradle plugin declares configuration-cache
  compatibility, verified with published-artifact consumers.

## [runtime-1.0.0] - 2026-10-15

### Changed

- Runtime and Analysis release independently. The BOM aligns core, test, and
  OpenTelemetry only. Stable Runtime APIs follow semantic versioning from 1.0.
- Runtime uses Kotlin language/API level 2.2 and library baseline 2.2.21; Java 17
  remains required. Kotlin support follows the rolling 18-month policy.
- Canvas registration is `provide`, construction lookup is `source`, and optional
  lookup is `sourceOrNull`. Replace `single`, `paint`, and `sourceOr`.
- Scoped `withMosaic` replaces `Canvas.create()`, public `MosaicImpl` construction,
  and `TestMosaicBuilder.build()`. Builders no longer take a TestScope.
- Reified Canvas lookups accept optional qualifiers.
- These changes break source and binary compatibility with 0.7.0; migrate and
  recompile consumers.

### Fixed

- Canvas closes owned resources once, in reverse creation order, even with
  repeated or concurrent close calls. Cleanup continues after a hook fails.
- Resource aliases do not duplicate cleanup or transfer borrowed/parent ownership.
- Test Canvas sources remain caller-owned. Each scoped test gets a fresh cache
  and a snapshot of substitutions and sources.

## [analysis-1.0.0] - 2026-10-15

### Changed

- Analysis has independent versioning and provisional tooling APIs. Compiler
  integration is selected automatically for compatible Kotlin versions.
- Metadata uses **contract version 6**; regenerate older dependency summaries.
  Analysis understands Core semantic revision 1 and rejects unsupported revisions.
- Extraction supports scoped `withMosaic` and the `provide`, `instance`, `source`,
  and `sourceOrNull` vocabulary. Construction lookup uses `CONSTRUCTION`.
- Conservative verification is unchanged: missing metadata remains `UNVERIFIED`,
  incompatible metadata fails, and unsupported Runtime semantics never become `VERIFIED`.

## [0.7.0] - 2026-10-05

### Added

- `Canvas.withMosaic` for one request lifetime: producer work inherits the calling
  coroutine; every exit cancels unfinished work and waits for cleanup.
- Typed, qualified, and CanvasKey `instance(...)` bindings for externally owned
  values that Canvas never closes, including on construction rollback. Qualifiers
  precede values, mirroring `single<T>("primary") { ... }`.
- Analysis recognition of supported `withMosaic` and `instance` usage, proven
  Tile-cycle detection, and configurable/suppressible recursive Tile/MultiTile
  policy with ERROR/WARNING/OFF severities. Proven cycles cannot be disabled.

### Changed

- MultiTile retains one terminal outcome per equal key. Per-key and chunk
  failures preserve successful siblings; strict bulk `compose` still throws on
  any requested failure. Returned Deferreds remain Mosaic-owned shared work.
- Analysis metadata moves to format 4 / `analysis-contract-3`; dependency
  summaries must be rebuilt with matching 0.7 tooling. Optional analysis still
  supports exactly Kotlin compiler/Gradle plugin 2.4.20.
- Spring, Ktor, and Micronaut examples use named Tiles, Canvas-supplied services,
  borrowed instances, child request Canvases, and suspending scoped handlers.

### Deprecated

- `Canvas.create()` at WARNING level. Move the complete handler invocation into
  `withMosaic` rather than retaining a Mosaic across requests.

### Fixed

- Present nullable MultiTile values succeed; omitted requested keys fail
  individually without discarding present values.
- Per-key and chunk provider failures no longer discard successful sibling keys.
- Tile body and attached-child failures use the same containment boundary with
  and without execution observation.

## [0.6.0] - 2026-10-03

### Added

- Delegated Tile naming with `val OrderTile by singleTile { ... }` and equivalent
  MultiTile factories. Aliases preserve the first name and the same cache identity.
- Supported execution observation for actual Tile executions and MultiTile batches,
  including caller context, shared-work relationships, and sanitized completion.
  Observation failures are isolated from Tile results.
- Optional `mosaic-opentelemetry` module and Canvas `tracing()` / `tracing { openTelemetry }`
  DSL. Spans use delegated Tile names, propagate coroutine context, and link shared work.
  The runtime BOM aligns the adapter alongside core and test libraries.

### Changed

- Canvas is a final Mosaic-owned class built with `canvas` or `withLayer`.
  External Canvas implementations and `MosaicCanvas` are removed; migrate to the DSL.
- Canvas construction shares concurrent dependency resolution, detects cycles, and
  cleans up local resources on failure/cancellation in reverse creation order.
- Kotlin compiler and Gradle plugin move to 2.4.20 with language/API level 2.4;
  runtime dependencies use stdlib 2.4.20 and coroutines 1.11.0. Runtime consumers
  require Kotlin 2.3.0 or later; JVM 17 remains the runtime target.
- Optional analysis supports exactly Kotlin compiler/Gradle plugin 2.4.20 and
  recognizes supported Mosaic-owned delegated Tile declarations. Older compiler
  summaries must be regenerated.

## [0.5.0] - 2026-09-26

### Changed

- MultiTile opportunistically combines newly pending uncached keys for the same
  MultiTile within a request Mosaic before scheduled execution begins. Ready work
  is not intentionally delayed; exact batch boundaries depend on scheduling.

## [0.4.0] - 2026-09-24

### Added

- Automatic discovery of safe application analysis roots from selected contracts,
  including local and dependency template/framework paths specialized for
  application receivers.
- `mosaicGraph` generates Markdown and Mermaid diagrams for local contracts,
  relevant dependencies, and application root findings.

### Changed

- Application roots are optional; explicitly configured roots replace automatic
  selection exactly.

## [0.3.0] - 2026-09-23

### Added

- Optional static Canvas contract analysis for supported Kotlin/JVM applications
  through the `org.buildmosaic.analysis` Gradle plugin.
- `APPLICATION` analysis roots and `LIBRARY` contract export. Published library
  contracts can be checked when an application consumes them.
- `STANDARD` enforcement, which warns on unverifiable paths, and `STRICT`
  enforcement, which also fails on those paths. Both fail proven missing Canvas
  requirements.
- Same-version analysis support and compiler artifacts for the Gradle plugin to
  resolve automatically.

### Changed

- Analysis extracts contracts incrementally during normal Kotlin compilation;
  production builds do not launch a second Mosaic compiler process.
- Mosaic runtime use no longer requires the old Mosaic registration plugins or
  KSP processors. The runtime BOM continues to align `mosaic-core` and
  `mosaic-test`.
- Low-level dependency-provider and analysis-extractor implementation types are
  no longer part of the Kotlin source API.

### Fixed

- Child Canvas construction can resolve bindings from its parent Canvas.
- Concurrent tile composition shares cached and in-flight results safely.
- Published runtime and test dependencies expose the coroutine libraries needed
  to compile against their public APIs.

### Analysis limits

Analysis is optional and currently supports Kotlin/JVM **2.2.10 only** within
the [documented pure `main` project boundary](mosaic-gradle-plugin/README.md#supported-project-boundary).
