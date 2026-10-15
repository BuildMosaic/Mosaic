# Changelog

Significant user-facing changes to Mosaic are documented here. Earlier versions
predate this maintained changelog; their release history is not reconstructed here.

## [Unreleased]

## [runtime-1.0.0] - Unreleased

### Changed

- Runtime (`mosaic-core`, `mosaic-test`, `mosaic-opentelemetry`, and `mosaic-bom`)
  releases independently of Analysis. The BOM aligns Runtime modules only.
- Stable Runtime APIs follow semantic versioning from 1.0.0. JVM ABI snapshots
  flag signature changes for review, including compatible additions; they do not
  validate behavior or full Kotlin source compatibility.
- Runtime uses Kotlin language/API level 2.2 and Kotlin library baseline 2.2.21,
  with JVM 17 unchanged. Exact Kotlin support is certified separately under the
  rolling 18-month policy; an ABI check does not certify a compiler version.
- Canvas-owned registration is `provide`, construction-time required lookup is
  `source`, and optional Canvas/Mosaic lookup is `sourceOrNull`. Replace `single`,
  `paint`, and `sourceOr`; no compatibility aliases remain. Construction stays eager.
- Reified Canvas `source<T>(qualifier)` and `sourceOrNull<T>(qualifier)` accept
  optional qualifiers, matching Mosaic lookup. CanvasKey and KClass lookups remain.
- Scoped `withMosaic` is the sole supported execution lifecycle. Remove calls to
  `Canvas.create()`, public `MosaicImpl` construction/subclassing, and
  `TestMosaicBuilder.build()`. Use `mosaicBuilder()` or `TestMosaicBuilder()` without
  a stored TestScope, and keep the complete handler or test inside `withMosaic`.
  Execution inherits the caller's coroutine context; every exit cancels unfinished
  producers and joins producer/attached-child cleanup.
- These 0.7.0-to-1.0 changes break Kotlin source and JVM binary compatibility;
  migrate and recompile consumers. The public `Mosaic` interface remains supported.

### Fixed

- `Canvas.close()` is idempotent and concurrency-safe. Owned resources close once
  in reverse successful creation order; all hooks are attempted, with later
  failures suppressed. Borrowed values and parent resources remain unaffected.
- Aliasing a locally owned resource does not duplicate cleanup. Returning a
  borrowed or inherited instance from `provide` does not transfer ownership.
- Test Canvas sources use borrowed `instance` bindings and are never closed by
  Canvas, including on failure.
- Test executions snapshot Tile/MultiTile substitutions and Canvas sources, with
  a fresh cache per invocation. Builder changes affect later executions only.

## [analysis-1.0.0] - Unreleased

### Changed

- Analysis releases independently of Runtime, with its own explicitly provisional
  tooling API. Compiler integration is selected automatically from bounded Kotlin
  ABI profiles; exact compiler/KGP and Runtime combinations require certification.
- Metadata uses **contract version 6**, replacing older format/analyzer headers.
  The complete `main` summary records Analysis/compiler producer provenance and
  a canonical payload hash. Producer release numbers alone do not determine
  compatibility; regenerate dependency summaries from 0.7.0 with compatible tooling.
- Core declares `Mosaic-Core-Analysis-Revision: 1`; summaries retain required Core
  semantic revisions, and Analysis 1.0 understands revision 1. Missing, malformed,
  conflicting, or unsupported Core revisions fail explicitly. Unsupported Runtime
  semantics never silently produce `VERIFIED`.
- Extraction recognizes the scoped `withMosaic` boundary and Canvas vocabulary:
  `provide`, `instance`, `source`, and `sourceOrNull`. Construction lookup uses
  `LookupKind.CONSTRUCTION` and the serialized `CONSTRUCTION` tag.
- Missing metadata on a reached dependency remains `UNVERIFIED`; present invalid
  or incompatible metadata fails. STANDARD warnings do not prove lookup safety;
  STRICT also fails uncertainty. Existing unknown boundaries remain conservative.

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
