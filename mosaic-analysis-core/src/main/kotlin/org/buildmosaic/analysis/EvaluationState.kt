package org.buildmosaic.analysis

internal enum class PathFeasibility {
  SUPPORTED,
  OPAQUE,
}

internal enum class PathConditionKind {
  SUPPORTED_ASSIGNMENT,
  OPAQUE_ALTERNATIVE,
}

// Allocated per evaluated value, never derived from source or diagnostic text.
internal class BooleanIdentity(val kind: PathConditionKind, val label: String)

internal data class PathCondition(
  val identity: BooleanIdentity,
  val value: Boolean,
) {
  val display: String get() = "${identity.label}=$value"
  val kind: PathConditionKind get() = identity.kind
}

internal sealed interface RuntimeArgument {
  data class CanvasValue(val state: CanvasState) : RuntimeArgument

  data class BooleanValue(val value: RuntimeBoolean) : RuntimeArgument
}

internal data class EvaluatedArguments(
  val context: EvaluationContext,
  val values: Map<ContractParameter, RuntimeArgument> = emptyMap(),
)

internal sealed interface RuntimeBoolean {
  data class Known(val value: Boolean) : RuntimeBoolean

  data class Symbolic(val identity: BooleanIdentity) : RuntimeBoolean
}

internal data class EvaluationContext(
  val values: Map<ContractParameter, RuntimeArgument> = emptyMap(),
  // Declaration-keyed slots and aliases belong to this invocation, not to a path condition.
  val activation: Int = 0,
  val aliases: Map<String, List<CanvasAlternative>> = emptyMap(),
  val assignments: Map<BooleanIdentity, Boolean> = emptyMap(),
  val conditions: List<PathCondition> = emptyList(),
  val feasibility: PathFeasibility = PathFeasibility.SUPPORTED,
  val cycleProofSupported: Boolean = true,
  val currentCanvas: CanvasState? = null,
  val knownReceiverType: String? = null,
  val dependencyPath: List<DependencyPathNode> = emptyList(),
  val activeTiles: List<ActiveTile> = emptyList(),
  val currentMosaic: Int? = null,
  val mosaics: Map<String, Int> = emptyMap(),
  val scope: String,
  val depth: Int = 0,
  val blocked: Boolean = false,
  val capturedOrigins: Set<CaptureOrigin> = emptySet(),
  val contextualEvidence: Set<EvidenceKind> = emptySet(),
)

internal data class ActiveTile(
  val identity: String,
  val contract: TileContract,
  val mosaic: Int?,
  val site: SourceLocation,
  val stable: Boolean,
  val mandatory: Boolean,
)

internal data class KnownBinding(
  val key: CanvasKeyIdentity,
  val site: SourceLocation,
  val evidence: EvidenceKind,
  val provenance: SourceLocation?,
  val capturedOrigin: CaptureOrigin?,
)

internal sealed interface CanvasState {
  data object Empty : CanvasState

  data class Layer(
    val id: String,
    val instanceId: String,
    val bindings: List<KnownBinding>,
    val hasUnknownRegistrations: Boolean,
    val parent: CanvasState,
    val site: SourceLocation,
  ) : CanvasState

  data class Unknown(
    val reason: String,
    val site: SourceLocation,
  ) : CanvasState

  data class Assumed(
    val contract: ExternalAssumption,
  ) : CanvasState

  data class Captured(
    val origin: CaptureOrigin,
    val state: CanvasState,
  ) : CanvasState
}

internal data class CanvasOutcome(
  val context: EvaluationContext,
  val state: CanvasState?,
)

internal data class CanvasAlternative(
  val state: CanvasState?,
  val assignments: Map<BooleanIdentity, Boolean>,
  val conditions: List<PathCondition>,
  val feasibility: PathFeasibility,
  val blocked: Boolean,
  val cycleProofSupported: Boolean,
)

internal sealed interface BranchDecision {
  data class Selected(val value: Boolean) : BranchDecision

  data object Skipped : BranchDecision
}

internal data class LookupResolution(
  val certainty: Certainty,
  val bindingSite: SourceLocation? = null,
  val canvasPath: List<CanvasPathNode> = emptyList(),
  val evidence: Set<EvidenceKind> = emptySet(),
  val assumptionIds: Set<String> = emptySet(),
  val capturedOrigins: Set<CaptureOrigin> = emptySet(),
  val reason: String,
  val bindingFactProvenance: SourceLocation? = null,
  val bindingCapturedOrigins: Set<CaptureOrigin> = emptySet(),
)

internal sealed interface TileResolution {
  data class Found(
    val contract: TileContract,
    val identity: String,
    val stable: Boolean = true,
  ) : TileResolution

  data class Missing(val target: String) : TileResolution

  data class Conflict(
    val target: String,
    val owners: List<String>,
  ) : TileResolution

  data class Unknown(
    val reason: String,
    val site: SourceLocation,
  ) : TileResolution
}

internal class ExpansionBudget(private val maximum: Int) {
  private var alternatives = 1

  fun reserveAlternative(): Boolean {
    if (alternatives >= maximum) return false
    alternatives++
    return true
  }
}
