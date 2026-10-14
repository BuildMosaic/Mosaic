# Executable scenario index

V = strict VERIFIED, M = exact missing key,
U = named UNVERIFIED boundary, D = deferred, R = rejected. Analysis concerns
selected-root Canvas availability, not general exceptions or deadlocks.
K names refer to analysis-core tests; C/B to compiler tests; G to TestKit.
`ExecutionCatalogTest` (EC) compiles one family and selects each named root.
`PublicApiCoverageTest` compiles one two-file family and selects public API roots;
`BinaryCanvasKeyExportTest` compiles one producer and one consumer. The
[public API matrix](PUBLIC_API_MATRIX.md) fixes support decisions and interactions.

Current production guarantee: reified, KClass, and CanvasKey forms share exact
runtime key identity for lookup, registration, and paint. Immutable top-level
CanvasKey values export explicit facts; unavailable binary exports and dynamic
key components stay named unknown. Mosaic.canvas carries its current Canvas.
Canvas layering, Tile creation, and compose/composeAsync retain evaluation order
and known-empty/known-nonempty/unknown MultiTile execution distinctions. Fresh
local Tile values have deferred template contracts and invocation-local
identities; unsupported capability captures remain named unknown.

| Row | Existing assertion/family; missing interaction added in EC |
|---|---|
| A1 | IrShape: argument work retained; EC ignored ordinary argument M/V |
| A2 | IrShape: named arguments, Canvas actual once; ActivationInvariant: actual order; EC receiver/named/vararg/spread |
| A3 | ActivationInvariant: missing/conflicting/limited callees, unknown callees; IrShape missing metadata M/U |
| A4 | DefaultBoundary: used/explicit local and binary defaults; EC literal defaults V |
| A5 | MosaicReceiverBoundary; inline binary accessor experiment; EC inline getter ordinary/Canvas U |
| A6 | IrShape Canvas helper prefix; EC getter/function prefix, discard, abort/result, empty/success/unknown prefix |
| B1 | IrShape lookup follows current and separate Mosaic receivers M/V |
| B2 | MosaicReceiverBoundary: helper, member extension, Canvas extension; regular parameter U |
| B3 | EC virtual Canvas method/getter U, final factory V; OverrideDispatchRegression |
| B4 | OverrideDispatchRegression slots; IrShape renamed slots with ordinary parameter; G enterprise |
| B5 | OverrideDispatchRegression relay/conflict; IrShape unrelated relay receiver |
| C1 | LayerSemantics alias once; CorrectnessRegression distinct constructions; ActivationInvariant per invocation; EC aliases |
| C2 | ActivationRegression boolean swap, canvas swap |
| C3 | PathStateRegression forwarded Boolean, separate opaque actuals |
| C4 | IdentityAndDiscovery declaration identity; DelegatedTile top-level/local factories M/V, local ordinary/delegated aliases, binary export M/V/U, arbitrary/property-reference/member/captured delegates U; EC member-dependent Tile U and Tile getter creation arguments |
| C5 | EC unused lambda V, invoked lambda/reference, returned/conditional callable escape U; DSL fixtures |
| D1 | EnterpriseAnalysis M/V; G binary enterprise M/V |
| D2 | LayerSemantics ancestor/nearest, late registration, child override |
| D3 | LayerSemantics unused eager, future child, duplicate, failed local |
| D4 | EC registration argument M, foreign builder/factory U; LayerSemantics eager providers |
| D5 | KeyAndOptionalSemantics qualifier/unknown/optional; EC optional argument M |
| D6 | IrShape mutable collections and arrays; KeyAndOptionalSemantics erasure |
| D7 | UnknownBoundary local over unknown parent, unknown registrations |
| E1 | InitializationBoundary stored/backing fields and binary constructors M/U/V |
| E2 | StoredFieldBoundary; InitializationBoundary domain V; EC object/delegate/super U |
| E3 | DefaultBoundary binary defaults with/without metadata U |
| E4 | IrShape const and inline producer/unchanged consumer experiments |
| F1 | ConditionAnalysis known/free/correlated/exclusive/opaque |
| F2 | PathStateRegression abort survivor, complementary continuing, nested branches |
| F3 | IdentityAndDiscovery MultiTile; IrShape MultiTile request and argument families |
| F4 | PathStateRegression async/synchronous, receiver and eager argument failures |
| F5 | BoundarySemantics bounded expansion; PathStateRegression incomplete opaque continuation |
| F6 | EC conditional/loop/try/safe-Elvis/mutation U and harmless control V |
| G1 | RootScope public helper D, specialization, no roots UNCONFIGURED |
| G2 | ActivationInvariant unknown input required/optional; CorrectnessRegression optional assumption |
| G3 | UnknownBoundary coexistence; ReportingAndPolicy strict/default/scoped assumptions |
| G4 | ReportingAndPolicy paths; ActivationInvariant provider capture; EC root reachability guard |
| H1 | IrShape DSL symbols/overloads/suspend; MosaicReceiverBoundary extension identities; override slots |
| H2 | ReferenceOwnership unchanged adapter; G platform removal/restoration |
| H3 | SummaryMetadata invalid/absent required headers and incompatible compiler records; UnknownBoundary conflicts/missing; G metadata loss/corruption |
| H4 | SummaryMetadata round trip and receiver fields; ReportingAndPolicy registry determinism |
| I1 | G fresh extraction: unchanged second source, rename, final-source removal |
| I2 | G rejected extraction removes previous output; packaging task dependencies |
| I3 | G separate binary artifacts body-only invalidation/restoration with adapter unchanged |
| I4 | G unsupported production configurations; compiler/toolchain rejection |
| I5 | G fresh extraction test-source exclusion and no roots; compiler task declared inputs/configuration cache |

## Operation/context matrix

Each cell names the adapter or interaction, not a Cartesian-product build.
All call adapters must use receiver → supplied actuals → used defaults →
eligibility/target → execution → continuing result. IR temporaries carry order.

| Form | Discard | Initializer | Argument/receiver | Ordinary return | Canvas return |
|---|---|---|---|---|---|
| Top-level/final | EC discard | EC aliases | IrShape actuals | IrShape helper | EC prefixes |
| Member | relay fixture | EC ordered receiver | enterprise template | enterprise handle | EC virtual method |
| Getter/setter | EC getters | stored property | receiver boundaries | initialization fixtures | EC virtual getter |
| Constructor | defaults fixture | stored domain | enterprise concrete receiver | domain construction | I: Canvas is final with an internal constructor; DSL consumers compile normally |
| Template/hook | slot fixture | ordinary call adapter | enterprise concrete receiver | enterprise response | same call adapter; arbitrary factory dispatch U |

Eligibility controls: direct V (enterprise/final); unsupported receiver U
(Mosaic extensions, virtual method/getter); inline U (ordinary/Canvas getter
and unchanged binary accessor); missing binary U (ordinary/constructor and
kernel Canvas reference). Defaults apply to functions/constructors only;
Kotlin accessors cannot declare optional parameters. Setters return Unit.
Immutable alias, expression/block return, consumed/discarded value, parameter
renaming, supplied/omitted defaults and source/binary equivalences retain the
same semantic expectations. Negative roots must reach the named operation.

Unsupported: arbitrary dispatch, Mosaic extension transfer, general defaults,
inline reconstruction, mutable/escaped provenance, lifecycle inference,
callbacks/delegates/reflection/complex control and complete JVM initialization.
Keep the final concrete receiver → inherited final template → direct protected
abstract suspend hook path strictly V with no assumptions.

Compiler facts exercised here: K2 represents named-argument reordering using IR
blocks/temporaries; external top-level declarations belong to package fragments,
not source files; unavailable binary defaults are error-expression stubs. Getter
bodies cannot suspend, so the Canvas getter prefix probe asserts its actual
UNVERIFIED Metrics lookup on an unknown returned Canvas, not an invented empty
Canvas or a generic root failure. Unknown ordinary Canvas-helper arguments are
processed by the same call plan: their work remains visible, a known missing
lookup is required, and harmless actuals can verify.

## Classifier boundary (Kotlin 2.4.20)

Both ordinary normalization and structural omission must use the same operation
proof. Arguments/receivers execute before that proof; callable bodies do not
execute on reference creation. The following covers the whitelist
and every structural early-return category. New assertions share EC's compilation.

| Classifier category | Evidence / expected outcome and EC roots |
|---|---|
| `EQEQ` | May dispatch user `equals`: `structuralEquality` U (implicit equals), `conditionalEquality` U (control). Harmless constructors isolate dispatch. Primitive/String operand proof: `primitiveEquality`, `stringEquality`, existing `harmlessControl` V. |
| `setOf`, `mutableSetOf`, `mapOf`, `mutableMapOf` | Kotlin 2.4.20 stdlib bytecode populates sets/maps; element/key hashing/equality may call user code. `setCallbacks`, `mutableSetCallbacks`, `mapCallbacks`, `mutableMapCallbacks`, `opaqueSet` U (implicit hashCode/equals). Scalar elements/keys and statically empty inputs V: `safeCollections`, `safeMaps`, `safeSetSpread`. Map values require no hashing proof. |
| `error` | Stdlib bytecode invokes message `Object.toString`: `errorCallback` U (implicit toString); `errorConstant` V within Canvas-availability scope (general exceptions are excluded). |
| Any constructor, String toString, primitive operations | Exact built-ins only; final scalar implementations do not dispatch user callbacks. Existing domain/primitive controls V. No package-prefix exemption. |
| `less`, `greater`, `lessOrEqual`, `greaterOrEqual` | Compiler primitive comparison intrinsics; any preceding user `compareTo` is an evaluated child. Existing harmless loop/comparison V. |
| empty collections, lists, arrays, `System.nanoTime` | No element callbacks during creation/read; evaluated actual work retained. `safeCollections` V even with user-valued elements; existing receiver/named/vararg/spread order unchanged. |
| Constants/value reads | No evaluated children. Existing alias and ordering assertions unchanged. |
| Lambda creation | Body deferred: `unusedLambda`, `deferredCreation` V; invocation/escape assertions remain U. |
| Function reference creation | Inspect evaluated bound receivers/arguments only. Exact `boundReference(kotlin.Boolean)` U (control), `directBoundReference` M (Metrics construction). Unbound references in `deferredCreation` V; referenced bodies stay deferred. |
| Function access | Actuals, callable escape, used defaults, intrinsic eligibility, final/non-inline/body/recursion checks all required. An unproven implicit callback must not fall through to an empty body proof. Existing inline/default/invocation tests unchanged. |
| Field read / object read | Field receiver must be inspected, delegated/capability/unavailable initialization cannot prove harmless. `conditionalField` U, `directField` M; Unit singleton remains the only harmless object exemption. Existing object/delegate tests U. |
| Variable and field/value writes | Child work retained; mutable capability declarations and callable field escapes cannot bypass normalizer boundaries. `mutableAlias` and selected `CallbackField` setter U (control). |
| Instance initializer marker | Constructor adapter owns explicit stored initialization; marker is not independent proof of a harmless constructor. Existing initialization/super tests retained. |
| Blocks, returns, casts, branches, loops, varargs/spreads, throws | Recursively prove evaluated children; no unconditional flattening of unsupported paths. Exception interpretation remains outside scope. Existing control/default tests retained. |
| All remaining nodes | No structural proof: localized U. No arbitrary operator/collection callback analysis added. |

The field fixture records the compiler's actual access representation rather than
assuming `@JvmField` guarantees a raw field node at the pre-lowering phase.

Structural cross-checks `conditionalSet`, `conditionalMap`, `conditionalError`
are U for control flow with unproved callbacks; `conditionalScalars` is V.
`conditionalEquality` takes user-valued parameters so constructor eligibility
cannot mask the equality decision.

Call eligibility audit also covers reference-typed actuals (KFunction as well as
Function types) and Mosaic extension ownership: `conditionalReferenceEscape`
and `conditionalExtension` U (control), even when the final callee body is empty.
These share the normal call boundary checks rather than a separate body shortcut.

## Receiver ownership and stable exports

`ExecutionCatalogTest` covers captured entry Canvas, nested factory/provider
ownership, direct and supplied lookups, stable Tile aliases, and unknown computed
Tile provenance. Its `computedWorkEntry` keeps getter and creation argument
work. The binary-default fixture covers stable Tile exports, custom getter
unknowns, and missing producer exports. These share existing compiler
compilations.

`ScopedAnalysisTest` covers direct `withMosaic`, Mosaic identity and aliases,
registration-time `instance` values, qualifier-first/interface bindings, positional
String-valued CanvasKeys, argument evaluation order, child layers, policy/correctness
recursion classification, source suppression scopes, selected binary cycles,
and unsupported helper/escaping/member boundaries.
