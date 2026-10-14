# Mosaic analysis core

`mosaic-analysis-core` is an internal, compiler-independent semantic kernel used
by Mosaic analysis build tooling. Its implementation is bundled into the
compiler and Gradle plugins; it is not published separately, an application
runtime dependency, or a BOM entry.
It accepts immutable, hand-authored contracts describing Canvas construction,
lookups, Tile composition, calls, conditions, and selected verification roots.
It verifies exact Canvas-key availability, eager local singleton construction,
and proven cyclic Tile result dependencies while preserving unknown boundaries
and source provenance.
Canvas arguments are evaluated eagerly at calls, while aliases and parameter
transfers preserve the identity and guarded alternatives of already-created values.

Boolean values have evaluation identities independent of their truth values and
feasibility evidence. An opaque actual retains correlation through immutable
parameter reads, forwarding, and negated guards, but neither truth assignment is
a supported feasibility witness. Separate opaque evaluations remain independent,
even when their expressions, source locations, and diagnostic text match.

Effect continuations discard an opaque condition only when complementary live
alternatives rejoin with compatible Canvas values. A construction abort keeps its
surviving alternative conditional; incomplete expansion remains uncertainty rather
than proven termination. Canvas aliases retain the conditions of their allocated
values for later reads. Reports preserve conditional paths, including alternatives
when findings are combined. Availability can be verified on every represented path
without proving which opaque branch is feasible.

UNKNOWN MultiTile execution explores execution and zero execution within the
existing alternative budget. A synchronous body construction failure therefore
leaves the zero-execution caller continuation, with opaque feasibility. Known-empty
execution skips the body; known-nonempty execution retains synchronous failure;
async body failure allows the launcher to continue. Receiver and argument
construction occurs before invocation. This models neither cache occupancy
nor general Deferred state.

```kotlin
val site = SourceLocation("example.Application", "Application.kt", 1, 1)
val metrics = CanvasKeyIdentity("example.Metrics")
val tile = TileContract(
  id = "example.MetricsTile",
  effects = listOf(
    Effect.Lookup(
      id = "metrics-source",
      canvas = CanvasExpression.Current,
      key = Fact.Known(metrics),
      kind = LookupKind.REQUIRED,
      site = SourceLocation("example.MetricsTile", "Tiles.kt", 4, 3),
    ),
  ),
)
val entry = CallableContract(
  id = "example.entry",
  effects = listOf(
    Effect.Compose(
      id = "entry-compose",
      canvas = CanvasExpression.Layer(
        id = "application",
        parent = CanvasExpression.Empty,
        bindings = listOf(Binding(Fact.Known(metrics), site = site)),
        site = site,
      ),
      tile = TileReference.Stable(tile.id),
      site = site,
    ),
  ),
)
val report = MosaicAnalyzer().analyze(
  AnalysisRequest(
    program = ModuleContract("application", tiles = listOf(tile), callables = listOf(entry)),
    roots = listOf(SelectedRoot("application-entry", entry.id)),
  ),
)
```

Reusable contracts are only reported as deferred until a selected root reaches
and specializes them. Public visibility does not select a root, and selecting no
roots produces an `UNCONFIGURED` report.

## Rules and receiver provenance

The semantic kernel owns Tile expansion and cycle classification. `MosaicRule`
centralizes public IDs, titles, default severities, configurability,
suppressibility, and correctness/policy classification. A small Mosaic identity
reference distinguishes current, established fresh, and unknown receivers;
Canvas equality is never Mosaic cache equality. Stable SingleTile synchronous
closed paths with established same-Mosaic identity are correctness errors.
Other stable recursion is configurable policy. Exported Tile contracts preserve
source-local suppression sites for later application verification.

See the canonical [analysis configuration reference](https://BuildMosaic.org/reference/analysis-configuration/)
for the rule table, policy precedence, suppression ownership, and supported
proof boundaries.

## Binary metadata

`SummaryCodec` writes deterministic UTF-8 JSON using generated
`kotlinx.serialization` serializers and internal, closed wire variants. Each
variant has a stable `kind` tag; metadata contains no JVM class names. The
evaluator model has no serialization annotations. The wire payload preserves
effects, arguments, receivers, captures, provenance, stable CanvasKey exports, overrides, and binary
locators. Unordered declarations, limitations, and locator entries are sorted;
effect and argument order is retained. Arguments are ordered parameter/value
entries, and duplicate parameters are rejected.

Format 4 has separate `formatVersion`, `semanticsVersion`, `toolVersion`,
`kotlinCompilerVersion`, `moduleId`, `sourceSet`, and `complete` header fields.
The reader accepts format 4, `analysis-contract-3`, Kotlin 2.4.20, and complete
`main` source-set summaries. The module identity must be nonblank and match the
payload. A nonblank producer version is required but does not determine semantic
compatibility. Regenerate incompatible summaries with the supported Mosaic
analysis toolchain before consuming them.

`payloadHash` is lowercase SHA-256 of the canonical compact UTF-8 JSON
serialization of the entire `payload` object (module, limitations, and binary
locators), without the envelope or hash field. It detects corruption; it does
not authenticate a producer or serve as a build-cache fingerprint. Missing
required fields, unknown kinds, partial output, and checksum mismatches fail
decoding.

The discovery resource is `META-INF/mosaic-analysis/v1/summary.json`;
compatibility is determined by the header, not the resource path. Serialization
is independent of Kotlin compiler and Gradle APIs.

The model intentionally supports only the expressions needed by the semantic
fixtures. It does not parse Kotlin source, inspect JARs, discover roots, infer
framework lifecycles or arbitrary dispatch, check generic types/subtypes, model
cache occupancy, Deferred awaits, or general deadlocks. Unsupported represented
behavior stays explicitly `UNVERIFIED`. Direct override transfer uses resolved receiver
relationships and positional Canvas slots; arbitrary virtual dispatch remains
outside the model. The wire format and API are provisional.

The in-memory tests validate kernel semantics. Compiler and Gradle module tests
cover source extraction, binary linking, and full-JAR build invalidation.
