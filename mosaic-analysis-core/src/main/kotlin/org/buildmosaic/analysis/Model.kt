package org.buildmosaic.analysis

/** A stable source location supplied by a future extractor or a hand-authored fixture. */
data class SourceLocation(
  val owner: String,
  val path: String,
  val line: Int,
  val column: Int,
)

enum class EvidenceKind {
  EXTRACTED,
  RUNTIME_MODEL,
  CAPTURED_FACT,
  EXTERNAL_ASSUMPTION,
}

sealed interface Fact<out T> {
  data class Known<T>(
    val value: T,
    val provenance: SourceLocation? = null,
    val evidence: EvidenceKind = EvidenceKind.EXTRACTED,
    val capturedOrigin: CaptureOrigin? = null,
  ) : Fact<T>

  data class Unknown(
    val reason: String,
    val site: SourceLocation,
  ) : Fact<Nothing>

  /** A stable key value exported by its producer; resolved only from selected metadata. */
  data class ExportedKey(val declaration: String, val site: SourceLocation) : Fact<Nothing>
}

/** The exact normalized runtime Canvas identity. Generic arguments are intentionally absent. */
data class CanvasKeyIdentity(
  val classId: String,
  val qualifier: String? = null,
)

enum class ParameterKind {
  CANVAS,
  BOOLEAN,
}

data class ContractParameter(
  val owner: String,
  val name: String,
  val kind: ParameterKind,
)

sealed interface BooleanExpression {
  data class Constant(val value: Boolean) : BooleanExpression

  data class ParameterValue(val parameter: ContractParameter) : BooleanExpression

  data class Opaque(
    val reason: String,
    val site: SourceLocation,
  ) : BooleanExpression
}

sealed interface Guard {
  data class Constant(val value: Boolean) : Guard

  data class BooleanParameter(
    val parameter: ContractParameter,
    val expected: Boolean = true,
  ) : Guard

  data class Opaque(
    val reason: String,
    val site: SourceLocation,
  ) : Guard
}

sealed interface ArgumentExpression {
  data class Canvas(val expression: CanvasExpression) : ArgumentExpression

  data class BooleanValue(val expression: BooleanExpression) : ArgumentExpression
}

/** Supplied expressions in caller evaluation order; use an iteration-ordered map. */
data class CallArguments(
  val values: Map<ContractParameter, ArgumentExpression> = emptyMap(),
)

sealed interface CanvasExpression {
  data object Empty : CanvasExpression

  data object Current : CanvasExpression

  data class ParameterValue(val parameter: ContractParameter) : CanvasExpression

  data class Layer(
    val id: String,
    val parent: CanvasExpression,
    val bindings: List<Binding> = emptyList(),
    val unknownRegistrations: List<UnknownRegistration> = emptyList(),
    val site: SourceLocation,
  ) : CanvasExpression

  data class Choice(
    val guard: Guard,
    val whenTrue: CanvasExpression,
    val whenFalse: CanvasExpression,
  ) : CanvasExpression

  /** An ordinary runtime reference, resolved against the selected registry at analysis time. */
  data class RuntimeCall(
    val target: String,
    val arguments: CallArguments = CallArguments(),
    val site: SourceLocation,
    val callerEffects: List<Effect> = emptyList(),
    val receiver: DispatchReceiver = DispatchReceiver.None,
    val virtualDispatch: Boolean = false,
  ) : CanvasExpression

  /** Read a previously evaluated value in the current invocation; never initializes it. */
  data class ValueReference(val id: String, val site: SourceLocation) : CanvasExpression

  data class WithEffects(
    val effects: List<Effect>,
    val result: CanvasExpression,
  ) : CanvasExpression

  /** Behavior captured by the caller, which can still contain ordinary runtime references. */
  data class Captured(
    val owner: String,
    val origin: CaptureOrigin,
    val expression: CanvasExpression,
    val faithfullyCaptured: Boolean,
    val site: SourceLocation,
  ) : CanvasExpression

  data class Assumption(
    val id: String,
    val site: SourceLocation,
  ) : CanvasExpression

  /** A runtime Canvas value initialized once per correlated invocation context. */
  data class Alias(
    val id: String,
    val expression: CanvasExpression,
  ) : CanvasExpression

  data class Unknown(
    val reason: String,
    val site: SourceLocation,
  ) : CanvasExpression
}

data class Binding(
  val key: Fact<CanvasKeyIdentity>,
  val constructorEffects: List<Effect> = emptyList(),
  val site: SourceLocation,
)

data class UnknownRegistration(
  val reason: String,
  val site: SourceLocation,
)

sealed interface CaptureOrigin {
  val declaration: String
  val artifact: String

  data class Inline(
    override val declaration: String,
    override val artifact: String,
    val contractHash: String,
  ) : CaptureOrigin

  data class Constant(
    override val declaration: String,
    override val artifact: String,
    val literal: String,
  ) : CaptureOrigin
}

sealed interface TileReference {
  data class Stable(
    val contractId: String,
    val receiverId: String? = null,
  ) : TileReference

  /** A binary property is usable only if its producer exported a proven stable Tile contract. */
  data class ExportedProperty(
    val contractId: String,
    val site: SourceLocation,
  ) : TileReference

  data class Alias(val reference: TileReference) : TileReference

  data class Fresh(
    val templateId: String,
    val allocationId: String,
    val invocationId: String,
  ) : TileReference

  data class Unknown(
    val reason: String,
    val site: SourceLocation,
  ) : TileReference
}

enum class LookupKind {
  REQUIRED,
  OPTIONAL,

  /** Required lookup while a Canvas provider is being constructed. */
  CONSTRUCTION,
}

enum class DiscoveryKind {
  COMPOSE,
  COMPOSE_ASYNC,
}

enum class MultiTileExecution {
  NOT_APPLICABLE,
  KNOWN_NON_EMPTY,
  KNOWN_EMPTY,
  UNKNOWN,
}

sealed interface DispatchReceiver {
  data object None : DispatchReceiver

  /** The callee receives the same dispatch object as its caller. */
  data object Forwarded : DispatchReceiver

  data class Concrete(val type: String) : DispatchReceiver

  data class Unknown(val reason: String) : DispatchReceiver
}

sealed interface Effect {
  val id: String
  val site: SourceLocation

  data class Lookup(
    override val id: String,
    val canvas: CanvasExpression,
    val key: Fact<CanvasKeyIdentity>,
    val kind: LookupKind,
    override val site: SourceLocation,
  ) : Effect

  data class Compose(
    override val id: String,
    val canvas: CanvasExpression,
    val tile: TileReference,
    val discovery: DiscoveryKind = DiscoveryKind.COMPOSE,
    val execution: MultiTileExecution = MultiTileExecution.NOT_APPLICABLE,
    override val site: SourceLocation,
    val mosaic: MosaicProvenance = MosaicProvenance.Unknown,
  ) : Effect

  /** Evaluate the Canvas before establishing one fresh standard Mosaic in this invocation. */
  data class EstablishMosaic(
    override val id: String,
    val canvas: CanvasExpression,
    override val site: SourceLocation,
  ) : Effect

  data class ConstructCanvas(
    override val id: String,
    val canvas: CanvasExpression,
    override val site: SourceLocation,
  ) : Effect

  data class Call(
    override val id: String,
    val target: String,
    val arguments: CallArguments = CallArguments(),
    override val site: SourceLocation,
    val receiver: DispatchReceiver = DispatchReceiver.None,
    val virtualDispatch: Boolean = false,
  ) : Effect

  data class Branch(
    override val id: String,
    val guard: Guard,
    val whenTrue: List<Effect>,
    val whenFalse: List<Effect> = emptyList(),
    override val site: SourceLocation,
  ) : Effect

  data class Captured(
    override val id: String,
    val owner: String,
    val origin: CaptureOrigin,
    val effects: List<Effect>,
    val faithfullyCaptured: Boolean,
    override val site: SourceLocation,
  ) : Effect

  data class Unknown(
    override val id: String,
    val reason: String,
    override val site: SourceLocation,
  ) : Effect
}

data class CanvasContract(
  val id: String,
  val parameters: List<ContractParameter> = emptyList(),
  val result: CanvasExpression,
  val site: SourceLocation,
  val reusable: Boolean = true,
)

data class TileContract(
  val id: String,
  val effects: List<Effect>,
  val site: SourceLocation,
  val reusable: Boolean = true,
  val multi: Boolean = false,
  val suppressions: List<MosaicSuppression> = emptyList(),
)

data class CallableContract(
  val id: String,
  val parameters: List<ContractParameter> = emptyList(),
  val effects: List<Effect>,
  val site: SourceLocation,
  val reusable: Boolean = true,
)

data class ModuleContract(
  val id: String,
  val canvases: List<CanvasContract> = emptyList(),
  val tiles: List<TileContract> = emptyList(),
  val callables: List<CallableContract> = emptyList(),
  val overrides: List<ResolvedOverride> = emptyList(),
  val keys: List<KeyContract> = emptyList(),
)

data class KeyContract(val id: String, val key: CanvasKeyIdentity, val site: SourceLocation)

data class ResolvedOverride(
  val receiverType: String,
  val baseId: String,
  val implementationId: String,
  val slots: List<OverrideSlot> = emptyList(),
)

data class OverrideSlot(
  val base: ContractParameter,
  val implementation: ContractParameter,
  val position: Int,
)

data class SelectedRoot(
  val id: String,
  val target: String,
  val arguments: CallArguments = CallArguments(),
  val site: SourceLocation? = null,
  val receiverType: String? = null,
  val selection: RootSelection = RootSelection.EXPLICIT,
)

enum class RootSelection {
  EXPLICIT,
  AUTOMATIC,
}

data class ExternalAssumption(
  val id: String,
  val guaranteedKeys: Set<CanvasKeyIdentity>,
  val provenance: SourceLocation,
)

data class AnalysisLimits(
  val alternativeBudget: Int = 64,
  val expansionDepth: Int = 64,
)

enum class AnalysisPolicy {
  DEFAULT,
  STRICT,
}

data class AnalysisRequest(
  val program: ModuleContract,
  val selectedDependencies: List<ModuleContract> = emptyList(),
  val roots: List<SelectedRoot> = emptyList(),
  val assumptions: List<ExternalAssumption> = emptyList(),
  val approvedAssumptions: Set<String> = emptySet(),
  val policy: AnalysisPolicy = AnalysisPolicy.DEFAULT,
  val limits: AnalysisLimits = AnalysisLimits(),
  val ruleSeverities: Map<String, MosaicRuleSeverity> = emptyMap(),
)

enum class Certainty {
  VERIFIED,
  MISSING,
  UNVERIFIED,
}

enum class FindingKind {
  TILE_RECURSION,
  REQUIRED_LOOKUP,
  OPTIONAL_LOOKUP,
  CONSTRUCTION_LOOKUP,
  DUPLICATE_BINDING,
  UNKNOWN_BOUNDARY,
  CONTRACT_CONFLICT,
  INCOMPLETE_ANALYSIS,
  CONFIGURATION,
}

data class DependencyPathNode(
  val label: String,
  val site: SourceLocation,
)

data class CanvasPathNode(
  val layerId: String,
  val site: SourceLocation?,
)

data class Finding(
  val rootId: String,
  val obligationId: String,
  val kind: FindingKind,
  val certainty: Certainty,
  val key: CanvasKeyIdentity? = null,
  val site: SourceLocation,
  val factProvenance: SourceLocation? = null,
  val capturedOrigins: Set<CaptureOrigin> = emptySet(),
  val bindingSite: SourceLocation? = null,
  val dependencyPath: List<DependencyPathNode> = emptyList(),
  val canvasPath: List<CanvasPathNode> = emptyList(),
  val pathCondition: List<String> = emptyList(),
  val evidence: Set<EvidenceKind> = emptySet(),
  val assumptionIds: Set<String> = emptySet(),
  val reason: String,
  /** Provider-key evidence, separate from the lookup key in [factProvenance]. */
  val bindingFactProvenance: SourceLocation? = null,
  val bindingCapturedOrigins: Set<CaptureOrigin> = emptySet(),
  val rule: MosaicRule? = null,
  val severity: MosaicRuleSeverity? = null,
  val suppressedAt: List<SourceLocation> = emptyList(),
)

enum class RootStatus {
  VERIFIED,
  FAILED,
  UNVERIFIED,
}

data class RootReport(
  val root: SelectedRoot,
  val status: RootStatus,
  val findings: List<Finding>,
  val specializedContracts: List<String>,
)

enum class ConfigurationStatus {
  CONFIGURED,
  UNCONFIGURED,
}

data class PolicyDecision(
  val passed: Boolean,
  val errors: List<Finding>,
  val warnings: List<Finding>,
)

data class AnalysisReport(
  val configurationStatus: ConfigurationStatus,
  val roots: List<RootReport>,
  val findings: List<Finding>,
  val deferredContracts: List<String>,
  val unknownBoundaries: List<Finding>,
  val limits: AnalysisLimits,
  val policy: AnalysisPolicy,
  val policyDecision: PolicyDecision,
)
