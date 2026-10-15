@file:Suppress(
  "CyclomaticComplexMethod",
  "LargeClass",
  "LongMethod",
  "LongParameterList",
  "NestedBlockDepth",
  "TooManyFunctions",
)

package org.buildmosaic.analysis

internal class RootEvaluator(
  private val registry: SelectedContractRegistry,
  private val assumptions: Map<String, List<ExternalAssumption>>,
  private val limits: AnalysisLimits,
  private val root: SelectedRoot,
  private val ruleSeverities: Map<String, MosaicRuleSeverity>,
) {
  private data class RecordedFinding(
    val finding: Finding,
    val feasibility: PathFeasibility,
    val assignments: Map<BooleanIdentity, Boolean>,
    val conditions: List<PathCondition>,
  ) {
    val supportedConditions: List<PathCondition> get() =
      conditions.filter {
        it.kind == PathConditionKind.SUPPORTED_ASSIGNMENT
      }
  }

  private val recordedFindings = mutableListOf<RecordedFinding>()
  private val specializedContracts = sortedSetOf<String>()
  private val budget = ExpansionBudget(limits.alternativeBudget)
  private var nextActivation = 1
  private var nextCanvasInstance = 0
  private var nextMosaicInstance = 0

  fun evaluate(): RootReport {
    val initial = EvaluationContext(scope = root.target, knownReceiverType = root.receiverType)
    when (val callable = registry.callable(root.target)) {
      is Resolution.Found -> evaluateRootCallable(callable.value, initial)
      is Resolution.Conflict -> addConflict(initial, root.target, callable.owners, rootSite())
      Resolution.Missing -> evaluateCanvasRoot(initial)
    }
    val findings = coalesceOpaqueLookupFindings()
    val status =
      when {
        findings.any {
          it.rule == null && it.certainty == Certainty.MISSING ||
            it.rule != null && it.suppressedAt.isEmpty() && it.severity == MosaicRuleSeverity.ERROR
        } -> RootStatus.FAILED
        findings.any { it.rule == null && it.certainty == Certainty.UNVERIFIED } -> RootStatus.UNVERIFIED
        else -> RootStatus.VERIFIED
      }
    return RootReport(root, status, findings.toList(), specializedContracts.toList())
  }

  private fun evaluateRootCallable(
    contract: CallableContract,
    context: EvaluationContext,
  ) {
    specializedContracts += contract.id
    val bound = bindRootParameters(context, contract.parameters)
    evaluateEffects(bound, contract.effects)
  }

  private fun evaluateCanvasRoot(context: EvaluationContext) {
    when (val canvas = registry.canvas(root.target)) {
      is Resolution.Found -> {
        specializedContracts += canvas.value.id
        val bound = bindRootParameters(context, canvas.value.parameters)
        bound.forEach { evaluateCanvas(canvas.value.result, it) }
      }
      is Resolution.Conflict -> addConflict(context, root.target, canvas.owners, rootSite())
      Resolution.Missing -> addUnknown(context, "root:${root.target}", rootSite(), "Selected root target is missing")
    }
  }

  private fun bindRootParameters(
    context: EvaluationContext,
    parameters: List<ContractParameter>,
  ): List<EvaluationContext> =
    evaluateArguments(context, root.arguments).map { evaluated ->
      bindArguments(
        evaluated,
        parameters,
        rootSite(),
        selectedInput = true,
      )
    }

  private fun evaluateEffects(
    initial: List<EvaluationContext>,
    effects: List<Effect>,
  ): List<EvaluationContext> {
    var contexts = initial
    effects.forEach { effect ->
      contexts =
        contexts.flatMap { context ->
          if (context.blocked) {
            listOf(context)
          } else {
            joinEffectContinuations(evaluateEffect(effect, context))
          }
        }.distinct()
    }
    return contexts
  }

  private fun evaluateEffect(
    effect: Effect,
    context: EvaluationContext,
  ): List<EvaluationContext> =
    when (effect) {
      is Effect.Lookup -> evaluateLookup(effect, context)
      is Effect.Compose -> evaluateCompose(effect, context)
      is Effect.EstablishMosaic ->
        evaluateCanvas(effect.canvas, context).map { outcome ->
          outcome.context.copy(
            mosaics = outcome.context.mosaics + (effect.id to nextMosaicInstance++),
            blocked = outcome.context.blocked || outcome.state == null,
          )
        }
      is Effect.ConstructCanvas ->
        evaluateCanvas(effect.canvas, context).map { outcome ->
          outcome.context.copy(blocked = outcome.context.blocked || outcome.state == null)
        }
      is Effect.Call -> evaluateCall(effect, context)
      is Effect.Branch -> evaluateBranch(effect, context)
      is Effect.Captured -> evaluateCaptured(effect, context)
      is Effect.Unknown -> {
        addUnknown(context, effect.id, effect.site, effect.reason)
        listOf(context.copy(cycleProofSupported = false))
      }
    }

  private fun evaluateLookup(
    effect: Effect.Lookup,
    context: EvaluationContext,
  ): List<EvaluationContext> {
    return evaluateCanvas(effect.canvas, context).map { outcome ->
      if (outcome.state == null) return@map outcome.context.copy(blocked = true)
      val resolvedKey = resolveKey(effect.key)
      if (resolvedKey is Fact.Unknown) {
        addLookupFinding(
          effect,
          outcome.context,
          null,
          LookupResolution(Certainty.UNVERIFIED, reason = "Canvas key is unknown: ${resolvedKey.reason}"),
          resolvedKey.site,
        )
        return@map outcome.context.copy(cycleProofSupported = false)
      }
      if (effect.kind == LookupKind.OPTIONAL) {
        addOptionalFinding(effect, outcome.context, "Optional lookup has no provider obligation")
        return@map outcome.context
      }
      val key = resolvedKey as Fact.Known
      val rawResolution = resolveLookup(outcome.state, key.value)
      val resolution = normalizeResolution(effect.kind, outcome.context, rawResolution)
      addLookupFinding(effect, outcome.context, key, resolution)
      val blocked =
        outcome.context.blocked ||
          effect.kind == LookupKind.CONSTRUCTION && rawResolution.certainty == Certainty.MISSING
      outcome.context.copy(
        blocked = blocked,
        cycleProofSupported = outcome.context.cycleProofSupported && resolution.certainty == Certainty.VERIFIED,
      )
    }
  }

  private fun normalizeResolution(
    kind: LookupKind,
    context: EvaluationContext,
    resolution: LookupResolution,
  ): LookupResolution =
    when {
      kind == LookupKind.OPTIONAL ->
        resolution.copy(certainty = Certainty.VERIFIED, reason = "Optional lookup does not require a provider")
      resolution.certainty == Certainty.MISSING && context.feasibility == PathFeasibility.OPAQUE ->
        resolution.copy(
          certainty = Certainty.UNVERIFIED,
          reason = "Absence occurs only under an opaque path condition",
        )
      else -> resolution
    }

  private fun evaluateCompose(
    effect: Effect.Compose,
    context: EvaluationContext,
  ): List<EvaluationContext> {
    val canvasOutcomes = evaluateCanvas(effect.canvas, context)
    if (effect.execution == MultiTileExecution.KNOWN_EMPTY) {
      return canvasOutcomes.map { it.context.copy(blocked = it.context.blocked || it.state == null) }
    }
    return canvasOutcomes.flatMap { outcome ->
      val state = outcome.state ?: return@flatMap listOf(outcome.context.copy(blocked = true))
      if (effect.execution != MultiTileExecution.UNKNOWN) {
        return@flatMap evaluateTile(effect, state, outcome.context)
      }
      splitOpaque(outcome.context, "${effect.id}: multi-tile executes", effect.id, effect.site)
        .flatMap { (decision, executionContext) ->
          if (decision == BranchDecision.Selected(true)) {
            evaluateTile(effect, state, executionContext)
          } else {
            // Zero execution and incomplete expansion both leave a caller continuation.
            listOf(executionContext)
          }
        }
    }
  }

  private fun evaluateTile(
    effect: Effect.Compose,
    canvas: CanvasState,
    context: EvaluationContext,
  ): List<EvaluationContext> {
    val found =
      when (val resolved = resolveTileReference(effect.tile, context)) {
        is TileResolution.Found -> resolved
        is TileResolution.Conflict -> {
          addConflict(context, effect.id, resolved.owners, effect.site)
          return listOf(context.copy(cycleProofSupported = false))
        }
        is TileResolution.Missing -> {
          addUnknown(context, effect.id, effect.site, "Tile contract ${resolved.target} is missing")
          return listOf(context.copy(cycleProofSupported = false))
        }
        is TileResolution.Unknown -> {
          addUnknown(context, effect.id, resolved.site, resolved.reason)
          return listOf(context.copy(cycleProofSupported = false))
        }
      }
    val (contract, identity) = found
    if (context.depth >= limits.expansionDepth) {
      addIncomplete(context, effect.id, effect.site, "Tile expansion depth limit reached")
      return listOf(context.copy(cycleProofSupported = false))
    }
    val mosaic =
      when (val provenance = effect.mosaic) {
        MosaicProvenance.Current -> context.currentMosaic
        is MosaicProvenance.Established -> context.mosaics[provenance.id]
        MosaicProvenance.Unknown -> null
      }
    val active =
      ActiveTile(
        identity,
        contract,
        mosaic,
        effect.site,
        found.stable,
        mandatory =
          context.cycleProofSupported && context.feasibility == PathFeasibility.SUPPORTED &&
            effect.discovery == DiscoveryKind.COMPOSE,
      )
    val returning = context.activeTiles.indexOfLast { it.identity == identity }
    if (returning >= 0) {
      addRecursion(context, effect, context.activeTiles.drop(returning), active)
      return listOf(context)
    }
    specializedContracts += contract.id
    val tilePath = context.dependencyPath + DependencyPathNode("${effect.discovery}:$identity", effect.site)
    val tileContext =
      context.copy(
        currentCanvas = canvas,
        dependencyPath = tilePath,
        activeTiles = context.activeTiles + active,
        currentMosaic = mosaic,
        mosaics = emptyMap(),
        cycleProofSupported = true,
        scope = identity,
        activation = nextActivation++,
        aliases = emptyMap(),
        depth = context.depth + 1,
      )
    return evaluateEffects(listOf(tileContext), contract.effects).map { child ->
      restoreCaller(
        child,
        context,
        propagateBlocked = effect.discovery == DiscoveryKind.COMPOSE,
      )
    }
  }

  private fun addRecursion(
    context: EvaluationContext,
    effect: Effect.Compose,
    participants: List<ActiveTile>,
    returning: ActiveTile,
  ) {
    // The first incoming edge only starts the work (possibly asynchronously). It is
    // not part of the closed result dependency. Every remaining edge must require a result.
    val closed = participants + returning
    val proven =
      closed.all { it.stable && !it.contract.multi && it.mosaic != null && it.mosaic == returning.mosaic } &&
        closed.drop(1).all { it.mandatory }
    val rule =
      when {
        proven -> MosaicRule.MOSAIC_CYCLIC_TILE_DEPENDENCY
        participants.any { !it.contract.multi } -> MosaicRule.MOSAIC_RECURSIVE_TILE
        else -> MosaicRule.MOSAIC_RECURSIVE_MULTITILE
      }
    val severity = ruleSeverities[rule.id] ?: rule.defaultSeverity
    if (severity == MosaicRuleSeverity.OFF) return
    val suppressions =
      if (rule.suppressible) {
        participants.flatMap { it.contract.suppressions }.filter { it.ruleId == rule.id }.map { it.site }.distinct()
      } else {
        emptyList()
      }
    record(
      context,
      Finding(
        rootId = root.id,
        obligationId = "${context.scope}:${effect.id}:recursion",
        kind = FindingKind.TILE_RECURSION,
        certainty = if (proven) Certainty.MISSING else Certainty.UNVERIFIED,
        site = effect.site,
        dependencyPath = closed.map { DependencyPathNode(it.contract.id, it.site) },
        pathCondition = context.conditions.map { it.display },
        rule = rule,
        severity = severity,
        suppressedAt = suppressions,
        reason =
          when (rule) {
            MosaicRule.MOSAIC_CYCLIC_TILE_DEPENDENCY ->
              "These Tile results depend circularly on unfinished work in the same Mosaic."
            MosaicRule.MOSAIC_RECURSIVE_TILE ->
              "Recursive Tile structure detected; a cyclic result dependency in the same Mosaic was not proven."
            MosaicRule.MOSAIC_RECURSIVE_MULTITILE ->
              "Mosaic cannot establish that recursive MultiTile requests use different keys or terminate."
          },
      ),
    )
  }

  private fun resolveTileReference(
    reference: TileReference,
    context: EvaluationContext,
  ): TileResolution =
    when (reference) {
      is TileReference.Alias -> resolveTileReference(reference.reference, context)
      is TileReference.Unknown -> TileResolution.Unknown(reference.reason, reference.site)
      is TileReference.Stable ->
        when (val contract = registry.tile(reference.contractId)) {
          is Resolution.Found -> TileResolution.Found(contract.value, stableIdentity(reference))
          is Resolution.Conflict -> TileResolution.Conflict(reference.contractId, contract.owners)
          Resolution.Missing -> TileResolution.Missing(reference.contractId)
        }
      is TileReference.ExportedProperty ->
        when (val contract = registry.tile(reference.contractId)) {
          is Resolution.Found -> TileResolution.Found(contract.value, reference.contractId)
          is Resolution.Conflict -> TileResolution.Conflict(reference.contractId, contract.owners)
          Resolution.Missing ->
            TileResolution.Unknown(
              "No exported stable Tile contract for property ${reference.contractId}",
              reference.site,
            )
        }
      is TileReference.Fresh ->
        when (val contract = registry.tile(reference.templateId)) {
          is Resolution.Found ->
            TileResolution.Found(
              contract.value,
              "${reference.templateId}@${reference.allocationId}#${reference.invocationId}:${context.activation}",
              stable = false,
            )
          is Resolution.Conflict -> TileResolution.Conflict(reference.templateId, contract.owners)
          Resolution.Missing -> TileResolution.Missing(reference.templateId)
        }
    }

  private fun stableIdentity(reference: TileReference.Stable): String =
    reference.receiverId?.let { "${reference.contractId}@$it" } ?: reference.contractId

  private fun evaluateCall(
    effect: Effect.Call,
    context: EvaluationContext,
  ): List<EvaluationContext> = invoke(effect, context, canvasResult = false).map { it.context }

  /** Shared target resolution, activation, ownership and continuation for every contract call. */
  private fun invoke(
    effect: Effect.Call,
    context: EvaluationContext,
    canvasResult: Boolean,
  ): List<CanvasOutcome> =
    evaluateArguments(context, effect.arguments).flatMap { evaluated ->
      val caller = evaluated.context
      if (caller.blocked) return@flatMap listOf(CanvasOutcome(caller, null))
      if (caller.depth >= limits.expansionDepth) {
        addIncomplete(caller, effect.id, effect.site, "Callable expansion depth limit reached")
        return@flatMap listOf(
          CanvasOutcome(
            caller.copy(cycleProofSupported = false),
            CanvasState.Unknown("Unresolved call result", effect.site),
          ),
        )
      }
      val receiverType =
        when (val receiver = effect.receiver) {
          DispatchReceiver.None -> null
          DispatchReceiver.Forwarded -> caller.knownReceiverType
          is DispatchReceiver.Concrete -> receiver.type
          is DispatchReceiver.Unknown -> null
        }
      if (effect.virtualDispatch && receiverType == null) {
        addUnknown(caller, effect.id, effect.site, "Virtual receiver is unresolved: ${effect.receiver}")
        return@flatMap listOf(
          CanvasOutcome(
            caller.copy(cycleProofSupported = false),
            CanvasState.Unknown("Unresolved call result", effect.site),
          ),
        )
      }
      val override =
        if (effect.virtualDispatch && receiverType != null) {
          when (val resolved = registry.directOverride(receiverType, effect.target)) {
            is Resolution.Found -> resolved.value
            is Resolution.Conflict -> {
              addConflict(caller, effect.id, resolved.owners, effect.site)
              return@flatMap listOf(
                CanvasOutcome(
                  caller.copy(cycleProofSupported = false),
                  CanvasState.Unknown("Unresolved call result", effect.site),
                ),
              )
            }
            Resolution.Missing -> {
              addUnknown(caller, effect.id, effect.site, "No direct override for $receiverType at ${effect.target}")
              return@flatMap listOf(
                CanvasOutcome(
                  caller.copy(cycleProofSupported = false),
                  CanvasState.Unknown("Unresolved call result", effect.site),
                ),
              )
            }
          }
        } else {
          null
        }
      val resolvedTarget = override?.implementationId ?: effect.target
      val contract =
        if (canvasResult) {
          registry.canvas(resolvedTarget).map { InvocationContract(it.id, it.parameters, emptyList(), it.result) }
        } else {
          registry.callable(resolvedTarget).map { InvocationContract(it.id, it.parameters, it.effects, null) }
        }
      when (val target = contract) {
        is Resolution.Found -> {
          specializedContracts += target.value.id
          val transferred =
            if (override == null) {
              evaluated
            } else {
              val mapping = override.slots.associate { it.base to it.implementation }
              if (evaluated.values.keys.any { it !in mapping }) {
                addUnknown(caller, effect.id, effect.site, "Override argument slots are incomplete")
                return@flatMap listOf(
                  CanvasOutcome(
                    caller.copy(cycleProofSupported = false),
                    CanvasState.Unknown("Unresolved call result", effect.site),
                  ),
                )
              }
              evaluated.copy(
                values = evaluated.values.mapKeys { (parameter, _) -> mapping.getValue(parameter) },
              )
            }
          val callee = bindArguments(transferred, target.value.parameters, effect.site)
          val nested =
            callee.copy(
              dependencyPath = caller.dependencyPath + DependencyPathNode("call:${effect.target}", effect.site),
              scope = resolvedTarget,
              depth = caller.depth + 1,
              knownReceiverType = receiverType,
              currentCanvas = null,
              currentMosaic = null,
              mosaics = emptyMap(),
            )
          evaluateEffects(listOf(nested), target.value.effects).flatMap { continuing ->
            val result = target.value.result
            if (result == null) {
              listOf(CanvasOutcome(continuing, if (continuing.blocked) null else CanvasState.Empty))
            } else {
              evaluateCanvas(result, continuing)
            }
          }.map { outcome -> CanvasOutcome(restoreCaller(outcome.context, caller), outcome.state) }
        }
        is Resolution.Conflict -> {
          addConflict(caller, effect.id, target.owners, effect.site)
          listOf(
            CanvasOutcome(
              caller.copy(cycleProofSupported = false),
              CanvasState.Unknown("Unresolved call result", effect.site),
            ),
          )
        }
        Resolution.Missing -> {
          addUnknown(
            caller,
            effect.id,
            effect.site,
            if (canvasResult) {
              "Runtime Canvas target is missing: ${effect.target}"
            } else {
              "Runtime callable target ${effect.target} is missing"
            },
          )
          listOf(
            CanvasOutcome(
              caller.copy(cycleProofSupported = false),
              CanvasState.Unknown("Unresolved call result", effect.site),
            ),
          )
        }
      }
    }

  private data class InvocationContract(
    val id: String,
    val parameters: List<ContractParameter>,
    val effects: List<Effect>,
    val result: CanvasExpression?,
  )

  private fun <T, R> Resolution<T>.map(transform: (T) -> R): Resolution<R> =
    when (this) {
      is Resolution.Found -> Resolution.Found(transform(value))
      is Resolution.Conflict -> this
      Resolution.Missing -> Resolution.Missing
    }

  private fun evaluateBranch(
    effect: Effect.Branch,
    context: EvaluationContext,
  ): List<EvaluationContext> =
    branch(effect.guard, context, effect.id, effect.site).flatMap { (decision, branchContext) ->
      when (decision) {
        is BranchDecision.Selected ->
          evaluateEffects(listOf(branchContext), if (decision.value) effect.whenTrue else effect.whenFalse)
        BranchDecision.Skipped -> listOf(branchContext)
      }
    }

  private fun evaluateCaptured(
    effect: Effect.Captured,
    context: EvaluationContext,
  ): List<EvaluationContext> {
    if (!effect.faithfullyCaptured) {
      addUnknown(
        context,
        effect.id,
        effect.site,
        "External capture ${effect.origin.declaration} was not faithfully captured",
      )
      return listOf(context.copy(cycleProofSupported = false))
    }
    val capturedContext =
      context.copy(
        dependencyPath = context.dependencyPath + DependencyPathNode("captured:${effect.owner}", effect.site),
        capturedOrigins = context.capturedOrigins + effect.origin,
        contextualEvidence = context.contextualEvidence + EvidenceKind.CAPTURED_FACT,
      )
    return evaluateEffects(listOf(capturedContext), effect.effects).map { restoreCaller(it, context) }
  }

  private fun evaluateCanvas(
    expression: CanvasExpression,
    context: EvaluationContext,
  ): List<CanvasOutcome> =
    if (context.blocked) {
      listOf(CanvasOutcome(context, null))
    } else {
      when (expression) {
        CanvasExpression.Empty -> listOf(CanvasOutcome(context, CanvasState.Empty))
        CanvasExpression.Current -> listOf(CanvasOutcome(context, context.currentCanvas ?: unknownCurrent(context)))
        is CanvasExpression.ParameterValue -> evaluateCanvasParameter(expression, context)
        is CanvasExpression.Layer -> evaluateLayer(expression, context)
        is CanvasExpression.Choice -> evaluateCanvasChoice(expression, context)
        is CanvasExpression.RuntimeCall -> evaluateCanvasCall(expression, context)
        is CanvasExpression.WithEffects ->
          evaluateEffects(listOf(context), expression.effects).flatMap { evaluateCanvas(expression.result, it) }
        is CanvasExpression.Captured -> evaluateCapturedCanvas(expression, context)
        is CanvasExpression.Assumption -> evaluateAssumption(expression, context)
        is CanvasExpression.Alias -> evaluateCanvasAlias(expression, context)
        is CanvasExpression.ValueReference -> {
          if (expression.id !in context.aliases) {
            addUnknown(context, expression.id, expression.site, "Evaluated Canvas value is unavailable")
            listOf(
              CanvasOutcome(
                context.copy(cycleProofSupported = false),
                CanvasState.Unknown("Uninitialized value", expression.site),
              ),
            )
          } else {
            readCanvasValue(expression.id, context)
          }
        }
        is CanvasExpression.Unknown -> {
          addUnknown(context, "canvas:${expression.site.owner}", expression.site, expression.reason)
          listOf(
            CanvasOutcome(
              context.copy(cycleProofSupported = false),
              CanvasState.Unknown(expression.reason, expression.site),
            ),
          )
        }
      }
    }

  private fun evaluateCanvasAlias(
    expression: CanvasExpression.Alias,
    context: EvaluationContext,
  ): List<CanvasOutcome> {
    if (expression.id in context.aliases) return readCanvasValue(expression.id, context)
    val outcomes = evaluateCanvas(expression.expression, context)
    val alternatives =
      outcomes.map { outcome ->
        CanvasAlternative(
          outcome.state,
          outcome.context.assignments,
          outcome.context.conditions,
          outcome.context.feasibility,
          outcome.context.blocked,
          outcome.context.cycleProofSupported,
        )
      }
    return outcomes.map { outcome ->
      outcome.copy(context = outcome.context.copy(aliases = outcome.context.aliases + (expression.id to alternatives)))
    }
  }

  private fun readCanvasValue(
    id: String,
    context: EvaluationContext,
  ): List<CanvasOutcome> {
    return context.aliases.getValue(id).let { alternatives ->
      alternatives.filter { alternative ->
        alternative.assignments.all { (symbol, value) ->
          context.assignments[symbol]?.let { it == value } ?: true
        }
      }.map { alternative ->
        CanvasOutcome(
          context.copy(
            assignments = context.assignments + alternative.assignments,
            conditions = (context.conditions + alternative.conditions).distinct(),
            feasibility = combineFeasibility(context.feasibility, alternative.feasibility),
            blocked = context.blocked || alternative.blocked,
            cycleProofSupported = context.cycleProofSupported && alternative.cycleProofSupported,
          ),
          alternative.state,
        )
      }
    }
  }

  private fun evaluateCanvasParameter(
    expression: CanvasExpression.ParameterValue,
    context: EvaluationContext,
  ): List<CanvasOutcome> {
    val value = context.values[expression.parameter] as? RuntimeArgument.CanvasValue
    if (value == null) {
      val site = rootSite()
      addUnknown(context, "canvas-parameter:${expression.parameter.name}", site, "Canvas parameter is unresolved")
      return listOf(
        CanvasOutcome(
          context.copy(cycleProofSupported = false),
          CanvasState.Unknown("Canvas parameter is unresolved", site),
        ),
      )
    }
    return listOf(CanvasOutcome(context, value.state))
  }

  private fun evaluateLayer(
    expression: CanvasExpression.Layer,
    context: EvaluationContext,
  ): List<CanvasOutcome> =
    evaluateCanvas(expression.parent, context).flatMap { parentOutcome ->
      val parent = parentOutcome.state
      if (parent == null) return@flatMap listOf(CanvasOutcome(parentOutcome.context.copy(blocked = true), null))
      constructLayer(expression, parent, parentOutcome.context)
    }

  private fun constructLayer(
    expression: CanvasExpression.Layer,
    parent: CanvasState,
    context: EvaluationContext,
  ): List<CanvasOutcome> {
    val knownBindings =
      expression.bindings.mapNotNull { binding ->
        val key = resolveKey(binding.key) as? Fact.Known ?: return@mapNotNull null
        KnownBinding(key.value, binding.site, key.evidence, key.provenance, key.capturedOrigin)
      }
    val unknownBindingSites = expression.bindings.mapNotNull { (resolveKey(it.key) as? Fact.Unknown)?.site }
    val unknownRegistrations = expression.unknownRegistrations.map { it.site } + unknownBindingSites
    val layer =
      CanvasState.Layer(
        expression.id,
        layerInstanceId(expression, context),
        knownBindings,
        unknownRegistrations.isNotEmpty(),
        parent,
        expression.site,
      )
    val duplicates = knownBindings.groupBy { it.key }.filterValues { it.size > 1 }
    if (duplicates.isNotEmpty()) {
      duplicates.toSortedMap(compareBy({ it.classId }, { it.qualifier ?: "" })).forEach { (key, bindings) ->
        addDuplicate(context, expression, key, bindings)
      }
      return listOf(CanvasOutcome(context.copy(blocked = true), null))
    }
    unknownRegistrations.forEachIndexed { index, site ->
      addUnknown(
        context,
        "${expression.id}:unknown-registration:$index",
        site,
        "Canvas layer contains a registration with an unknown key or effect",
      )
    }
    var constructorContexts =
      listOf(
        context.copy(
          currentCanvas = layer,
          cycleProofSupported = context.cycleProofSupported && unknownRegistrations.isEmpty(),
        ),
      )
    expression.bindings.forEach { binding ->
      constructorContexts =
        constructorContexts.flatMap { previous ->
          evaluateEffects(listOf(previous.copy(blocked = false)), binding.constructorEffects).map { evaluated ->
            evaluated.copy(blocked = previous.blocked || evaluated.blocked)
          }
        }
    }
    return constructorContexts.map { constructorContext ->
      val restored = constructorContext.copy(currentCanvas = context.currentCanvas)
      CanvasOutcome(restored, if (restored.blocked) null else layer)
    }
  }

  private fun evaluateCanvasChoice(
    expression: CanvasExpression.Choice,
    context: EvaluationContext,
  ): List<CanvasOutcome> =
    branch(expression.guard, context, "canvas-choice", rootSite()).flatMap { (decision, branchContext) ->
      when (decision) {
        is BranchDecision.Selected ->
          evaluateCanvas(if (decision.value) expression.whenTrue else expression.whenFalse, branchContext)
        BranchDecision.Skipped ->
          listOf(CanvasOutcome(branchContext, CanvasState.Unknown("Alternative expansion was skipped", rootSite())))
      }
    }

  private fun evaluateCanvasCall(
    expression: CanvasExpression.RuntimeCall,
    context: EvaluationContext,
  ): List<CanvasOutcome> =
    evaluateEffects(listOf(context), expression.callerEffects).flatMap { prepared ->
      invoke(
        Effect.Call(
          expression.target,
          expression.target,
          expression.arguments,
          expression.site,
          expression.receiver,
          expression.virtualDispatch,
        ),
        prepared,
        canvasResult = true,
      )
    }

  private fun evaluateCapturedCanvas(
    expression: CanvasExpression.Captured,
    context: EvaluationContext,
  ): List<CanvasOutcome> {
    if (!expression.faithfullyCaptured) {
      val reason = "External capture ${expression.origin.declaration} was not faithfully captured"
      addUnknown(context, expression.owner, expression.site, reason)
      return listOf(
        CanvasOutcome(context.copy(cycleProofSupported = false), CanvasState.Unknown(reason, expression.site)),
      )
    }
    val nested =
      context.copy(
        dependencyPath = context.dependencyPath + DependencyPathNode("captured:${expression.owner}", expression.site),
        capturedOrigins = context.capturedOrigins + expression.origin,
        contextualEvidence = context.contextualEvidence + EvidenceKind.CAPTURED_FACT,
      )
    return evaluateCanvas(expression.expression, nested).map { outcome ->
      CanvasOutcome(
        restoreCaller(outcome.context, context),
        outcome.state?.let { CanvasState.Captured(expression.origin, it) },
      )
    }
  }

  private fun evaluateAssumption(
    expression: CanvasExpression.Assumption,
    context: EvaluationContext,
  ): List<CanvasOutcome> {
    val candidates = assumptions[expression.id].orEmpty()
    if (candidates.isEmpty()) {
      addUnknown(context, expression.id, expression.site, "External assumption is missing")
      return listOf(
        CanvasOutcome(
          context.copy(cycleProofSupported = false),
          CanvasState.Unknown("Missing assumption", expression.site),
        ),
      )
    }
    return if (candidates.size == 1) {
      listOf(CanvasOutcome(context, CanvasState.Assumed(candidates.single())))
    } else {
      addConflict(context, expression.id, candidates.map { it.provenance.owner }.sorted(), expression.site)
      listOf(
        CanvasOutcome(
          context.copy(cycleProofSupported = false),
          CanvasState.Unknown("Conflicting assumptions", expression.site),
        ),
      )
    }
  }

  // Actual expressions belong to the caller activation. Never install a formal while
  // evaluating actuals: recursive calls use the same parameter declarations.
  private fun evaluateArguments(
    context: EvaluationContext,
    arguments: CallArguments,
  ): List<EvaluatedArguments> {
    var evaluated = listOf(EvaluatedArguments(context))
    arguments.values.forEach { (parameter, argument) ->
      evaluated =
        evaluated.flatMap { actuals ->
          if (actuals.context.blocked) return@flatMap listOf(actuals)
          when (argument) {
            is ArgumentExpression.Canvas ->
              evaluateCanvas(argument.expression, actuals.context).map { outcome ->
                actuals.copy(
                  context = outcome.context.copy(blocked = outcome.context.blocked || outcome.state == null),
                  values =
                    actuals.values + listOfNotNull(outcome.state?.let { parameter to RuntimeArgument.CanvasValue(it) }),
                )
              }
            is ArgumentExpression.BooleanValue ->
              listOf(
                actuals.copy(
                  values =
                    actuals.values +
                      (parameter to RuntimeArgument.BooleanValue(resolveBoolean(argument.expression, actuals.context))),
                ),
              )
          }
        }
    }
    return evaluated
  }

  private fun bindArguments(
    evaluated: EvaluatedArguments,
    parameters: List<ContractParameter>,
    site: SourceLocation,
    selectedInput: Boolean = false,
  ): EvaluationContext {
    val context = evaluated.context
    (evaluated.values.keys - parameters.toSet()).forEach { parameter ->
      addUnknown(context, "argument:${parameter.name}", site, "Unexpected argument ${parameter.name}")
    }
    val values =
      parameters.associateWith { parameter ->
        val supplied = evaluated.values[parameter]
        val valid =
          when (parameter.kind) {
            ParameterKind.CANVAS -> supplied is RuntimeArgument.CanvasValue
            ParameterKind.BOOLEAN -> supplied is RuntimeArgument.BooleanValue
          }
        if (valid) {
          requireNotNull(supplied)
        } else {
          if (supplied != null || !selectedInput) {
            val kind = if (parameter.kind == ParameterKind.CANVAS) "Canvas" else "Boolean"
            val reason = if (supplied == null) "Missing $kind argument" else "Incompatible $kind argument"
            addUnknown(
              context,
              "argument:${parameter.name}",
              site,
              "$reason ${parameter.name}",
            )
          }
          when (parameter.kind) {
            ParameterKind.CANVAS ->
              RuntimeArgument.CanvasValue(
                CanvasState.Unknown("Unresolved Canvas input ${parameter.name}", site),
              )
            ParameterKind.BOOLEAN ->
              RuntimeArgument.BooleanValue(
                if (selectedInput && supplied == null) {
                  symbolicBoolean(parameter.name, PathConditionKind.SUPPORTED_ASSIGNMENT)
                } else {
                  opaqueBoolean("Missing or incompatible Boolean argument")
                },
              )
          }
        }
      }
    return context.copy(values = values, activation = nextActivation++, aliases = emptyMap())
  }

  private fun resolveBoolean(
    expression: BooleanExpression,
    context: EvaluationContext,
  ): RuntimeBoolean =
    when (expression) {
      is BooleanExpression.Constant -> RuntimeBoolean.Known(expression.value)
      is BooleanExpression.Opaque -> opaqueBoolean(expression.reason)
      is BooleanExpression.ParameterValue ->
        (context.values[expression.parameter] as? RuntimeArgument.BooleanValue)?.value
          ?: opaqueBoolean("Unresolved Boolean parameter ${expression.parameter.name}")
    }

  private fun branch(
    guard: Guard,
    context: EvaluationContext,
    obligationId: String,
    site: SourceLocation,
  ): List<Pair<BranchDecision, EvaluationContext>> =
    when (guard) {
      is Guard.Constant -> listOf(BranchDecision.Selected(guard.value) to context)
      is Guard.Opaque -> splitOpaque(context, guard.reason, obligationId, site)
      is Guard.BooleanParameter -> splitBooleanParameter(guard, context, obligationId, site)
    }

  private fun splitBooleanParameter(
    guard: Guard.BooleanParameter,
    context: EvaluationContext,
    obligationId: String,
    site: SourceLocation,
  ): List<Pair<BranchDecision, EvaluationContext>> {
    val value =
      (context.values[guard.parameter] as? RuntimeArgument.BooleanValue)?.value
        ?: opaqueBoolean("Unresolved Boolean parameter ${guard.parameter.name}")
    return when (value) {
      is RuntimeBoolean.Known -> listOf(BranchDecision.Selected(value.value == guard.expected) to context)
      is RuntimeBoolean.Symbolic -> splitBoolean(value, guard.expected, context, obligationId, site)
    }
  }

  private fun symbolicBoolean(
    label: String,
    kind: PathConditionKind,
  ) = RuntimeBoolean.Symbolic(BooleanIdentity(kind, label))

  private fun opaqueBoolean(reason: String) = symbolicBoolean("opaque($reason)", PathConditionKind.OPAQUE_ALTERNATIVE)

  private fun splitOpaque(
    context: EvaluationContext,
    reason: String,
    obligationId: String,
    site: SourceLocation,
  ): List<Pair<BranchDecision, EvaluationContext>> =
    splitBoolean(opaqueBoolean(reason), true, context, obligationId, site)

  private fun splitBoolean(
    value: RuntimeBoolean.Symbolic,
    expected: Boolean,
    context: EvaluationContext,
    obligationId: String,
    site: SourceLocation,
  ): List<Pair<BranchDecision, EvaluationContext>> {
    val assigned = context.assignments[value.identity]
    val alternatives = if (assigned == null) listOf(false, true) else listOf(assigned)
    if (assigned == null && !budget.reserveAlternative()) {
      addIncomplete(context, obligationId, site, "Alternative expansion budget exceeded")
      return listOf(BranchDecision.Skipped to context)
    }
    return alternatives.map { actual ->
      val condition = PathCondition(value.identity, actual)
      BranchDecision.Selected(actual == expected) to
        context.copy(
          assignments = context.assignments + (value.identity to actual),
          // Reading a previously assigned opaque value reestablishes its guard even
          // when an earlier effect joined both alternatives at its continuation.
          conditions = (context.conditions + condition).distinct(),
          feasibility =
            if (condition.kind == PathConditionKind.OPAQUE_ALTERNATIVE) PathFeasibility.OPAQUE else context.feasibility,
        )
    }
  }

  private fun resolveLookup(
    canvas: CanvasState,
    key: CanvasKeyIdentity,
    path: List<CanvasPathNode> = emptyList(),
  ): LookupResolution =
    when (canvas) {
      CanvasState.Empty ->
        LookupResolution(
          Certainty.MISSING,
          canvasPath = path,
          reason = "Exact key is absent from the complete Canvas chain",
        )
      is CanvasState.Unknown ->
        LookupResolution(
          Certainty.UNVERIFIED,
          canvasPath = path + CanvasPathNode("unknown", canvas.site),
          reason = "Canvas boundary is unknown: ${canvas.reason}",
        )
      is CanvasState.Assumed -> resolveAssumedLookup(canvas.contract, key, path)
      is CanvasState.Captured ->
        resolveLookup(canvas.state, key, path).let { resolution ->
          resolution.copy(
            evidence = resolution.evidence + EvidenceKind.CAPTURED_FACT,
            capturedOrigins = resolution.capturedOrigins + canvas.origin,
          )
        }
      is CanvasState.Layer -> {
        val layerPath = path + CanvasPathNode(canvas.id, canvas.site)
        val local = canvas.bindings.firstOrNull { it.key == key }
        when {
          local != null ->
            LookupResolution(
              Certainty.VERIFIED,
              local.site,
              layerPath,
              setOf(local.evidence),
              reason = "Exact local binding selected",
              capturedOrigins = setOfNotNull(local.capturedOrigin),
              bindingFactProvenance = local.provenance,
              bindingCapturedOrigins = setOfNotNull(local.capturedOrigin),
            )
          canvas.hasUnknownRegistrations ->
            LookupResolution(
              Certainty.UNVERIFIED,
              canvasPath = layerPath,
              reason = "An unknown local registration may supply or shadow the key",
            )
          else -> resolveLookup(canvas.parent, key, layerPath)
        }
      }
    }

  private fun resolveAssumedLookup(
    assumption: ExternalAssumption,
    key: CanvasKeyIdentity,
    path: List<CanvasPathNode>,
  ): LookupResolution =
    if (key in assumption.guaranteedKeys) {
      LookupResolution(
        Certainty.VERIFIED,
        assumption.provenance,
        path + CanvasPathNode("assumption:${assumption.id}", assumption.provenance),
        setOf(EvidenceKind.EXTERNAL_ASSUMPTION),
        setOf(assumption.id),
        reason = "Exact key is guaranteed by a labeled external assumption",
      )
    } else {
      LookupResolution(
        Certainty.UNVERIFIED,
        canvasPath = path + CanvasPathNode("assumption:${assumption.id}", assumption.provenance),
        evidence = setOf(EvidenceKind.EXTERNAL_ASSUMPTION),
        assumptionIds = setOf(assumption.id),
        reason = "The assumption does not establish whether the remaining Canvas supplies this key",
      )
    }

  private fun addLookupFinding(
    effect: Effect.Lookup,
    context: EvaluationContext,
    key: Fact.Known<CanvasKeyIdentity>?,
    resolution: LookupResolution,
    unknownFactProvenance: SourceLocation? = null,
  ) {
    val kind =
      when (effect.kind) {
        LookupKind.REQUIRED -> FindingKind.REQUIRED_LOOKUP
        LookupKind.OPTIONAL -> FindingKind.OPTIONAL_LOOKUP
        LookupKind.CONSTRUCTION -> FindingKind.CONSTRUCTION_LOOKUP
      }
    record(
      context,
      Finding(
        rootId = root.id,
        obligationId = "${context.scope}:${effect.id}",
        kind = kind,
        certainty = resolution.certainty,
        key = key?.value,
        site = effect.site,
        factProvenance = key?.provenance ?: unknownFactProvenance,
        capturedOrigins = context.capturedOrigins + resolution.capturedOrigins + setOfNotNull(key?.capturedOrigin),
        bindingSite = resolution.bindingSite,
        bindingFactProvenance = resolution.bindingFactProvenance,
        bindingCapturedOrigins = resolution.bindingCapturedOrigins,
        dependencyPath = context.dependencyPath,
        canvasPath = resolution.canvasPath,
        pathCondition = context.conditions.map { it.display },
        evidence = context.contextualEvidence + resolution.evidence + listOfNotNull(key?.evidence),
        assumptionIds = resolution.assumptionIds,
        reason = resolution.reason,
      ),
    )
  }

  private fun addOptionalFinding(
    effect: Effect.Lookup,
    context: EvaluationContext,
    reason: String,
  ) {
    val resolvedKey = resolveKey(effect.key)
    val key = resolvedKey as? Fact.Known
    record(
      context,
      Finding(
        rootId = root.id,
        obligationId = "${context.scope}:${effect.id}",
        kind = FindingKind.OPTIONAL_LOOKUP,
        certainty = Certainty.VERIFIED,
        key = key?.value,
        site = effect.site,
        factProvenance = key?.provenance ?: (resolvedKey as? Fact.Unknown)?.site,
        capturedOrigins = context.capturedOrigins + setOfNotNull(key?.capturedOrigin),
        dependencyPath = context.dependencyPath,
        pathCondition = context.conditions.map { it.display },
        evidence = context.contextualEvidence + listOfNotNull(key?.evidence),
        reason = reason,
      ),
    )
  }

  private fun resolveKey(fact: Fact<CanvasKeyIdentity>): Fact<CanvasKeyIdentity> =
    when (fact) {
      is Fact.Known, is Fact.Unknown -> fact
      is Fact.ExportedKey ->
        when (val exported = registry.key(fact.declaration)) {
          is Resolution.Found -> Fact.Known(exported.value.key, exported.value.site)
          is Resolution.Conflict -> Fact.Unknown("Conflicting exported CanvasKey ${fact.declaration}", fact.site)
          Resolution.Missing -> Fact.Unknown("No exported stable CanvasKey fact for ${fact.declaration}", fact.site)
        }
    }

  private fun addDuplicate(
    context: EvaluationContext,
    layer: CanvasExpression.Layer,
    key: CanvasKeyIdentity,
    bindings: List<KnownBinding>,
  ) {
    record(
      context,
      Finding(
        rootId = root.id,
        obligationId = "${context.scope}:${layer.id}:duplicate:${key.classId}:${key.qualifier}",
        kind = FindingKind.DUPLICATE_BINDING,
        certainty =
          if (context.feasibility == PathFeasibility.OPAQUE) Certainty.UNVERIFIED else Certainty.MISSING,
        key = key,
        site = bindings.last().site,
        bindingSite = bindings.first().site,
        capturedOrigins = context.capturedOrigins,
        dependencyPath = context.dependencyPath,
        canvasPath = listOf(CanvasPathNode(layer.id, layer.site)),
        pathCondition = context.conditions.map { it.display },
        evidence = context.contextualEvidence,
        reason =
          if (context.feasibility == PathFeasibility.OPAQUE) {
            "Duplicate local Canvas bindings occur only under an opaque path condition"
          } else {
            "Definite duplicate local Canvas bindings make layer construction invalid"
          },
      ),
    )
  }

  private fun addUnknown(
    context: EvaluationContext,
    obligationId: String,
    site: SourceLocation,
    reason: String,
  ) {
    record(
      context,
      Finding(
        rootId = root.id,
        obligationId = "${context.scope}:$obligationId",
        kind = FindingKind.UNKNOWN_BOUNDARY,
        certainty = Certainty.UNVERIFIED,
        site = site,
        capturedOrigins = context.capturedOrigins,
        dependencyPath = context.dependencyPath,
        pathCondition = context.conditions.map { it.display },
        evidence = context.contextualEvidence,
        reason = reason,
      ),
    )
  }

  private fun addConflict(
    context: EvaluationContext,
    obligationId: String,
    owners: List<String>,
    site: SourceLocation,
  ) {
    record(
      context,
      Finding(
        rootId = root.id,
        obligationId = "${context.scope}:$obligationId",
        kind = FindingKind.CONTRACT_CONFLICT,
        certainty = Certainty.UNVERIFIED,
        site = site,
        capturedOrigins = context.capturedOrigins,
        dependencyPath = context.dependencyPath,
        pathCondition = context.conditions.map { it.display },
        evidence = context.contextualEvidence,
        reason = "Conflicting selected definitions from ${owners.joinToString()}",
      ),
    )
  }

  private fun addIncomplete(
    context: EvaluationContext,
    obligationId: String,
    site: SourceLocation,
    reason: String,
  ) {
    record(
      context,
      Finding(
        rootId = root.id,
        obligationId = "${context.scope}:$obligationId",
        kind = FindingKind.INCOMPLETE_ANALYSIS,
        certainty = Certainty.UNVERIFIED,
        site = site,
        capturedOrigins = context.capturedOrigins,
        dependencyPath = context.dependencyPath,
        pathCondition = context.conditions.map { it.display },
        evidence = context.contextualEvidence,
        reason = reason,
      ),
    )
  }

  private fun record(
    context: EvaluationContext,
    finding: Finding,
  ) {
    recordedFindings +=
      RecordedFinding(
        finding,
        context.feasibility,
        context.assignments.filterKeys { it.kind == PathConditionKind.SUPPORTED_ASSIGNMENT },
        context.conditions,
      )
  }

  private fun coalesceOpaqueLookupFindings(): List<Finding> {
    val lookupKinds =
      setOf(
        FindingKind.REQUIRED_LOOKUP,
        FindingKind.OPTIONAL_LOOKUP,
        FindingKind.CONSTRUCTION_LOOKUP,
      )
    val opaque = recordedFindings.filter { it.feasibility == PathFeasibility.OPAQUE && it.finding.kind in lookupKinds }
    val ordinary = recordedFindings - opaque.toSet()
    val merged =
      opaque.groupBy { record ->
        listOf(
          record.finding.rootId,
          record.finding.obligationId,
          record.finding.kind,
          record.finding.key,
          record.finding.dependencyPath,
          record.assignments,
          record.supportedConditions,
        )
      }.values.map { alternatives -> mergeOpaqueLookupAlternatives(alternatives) }
    return (ordinary.map { it.finding } + merged).distinct()
  }

  private fun mergeOpaqueLookupAlternatives(alternatives: List<RecordedFinding>): Finding {
    val findings = alternatives.map { it.finding }
    val first = findings.first()
    val pathCondition = describeAlternativePaths(alternatives)
    if (findings.all { it.certainty == Certainty.VERIFIED }) {
      return first.copy(
        pathCondition = pathCondition,
        capturedOrigins = findings.flatMapTo(mutableSetOf()) { it.capturedOrigins },
        evidence = findings.flatMapTo(mutableSetOf()) { it.evidence },
        assumptionIds = findings.flatMapTo(mutableSetOf()) { it.assumptionIds },
        bindingCapturedOrigins = findings.flatMapTo(mutableSetOf()) { it.bindingCapturedOrigins },
        reason = "Every opaque Canvas alternative supplies the exact key",
      )
    }
    return first.copy(
      certainty = Certainty.UNVERIFIED,
      bindingSite = null,
      bindingFactProvenance = null,
      bindingCapturedOrigins = findings.flatMapTo(mutableSetOf()) { it.bindingCapturedOrigins },
      pathCondition = pathCondition,
      capturedOrigins = findings.flatMapTo(mutableSetOf()) { it.capturedOrigins },
      evidence = findings.flatMapTo(mutableSetOf()) { it.evidence },
      assumptionIds = findings.flatMapTo(mutableSetOf()) { it.assumptionIds },
      reason = "Canvas availability is not established on every opaque path",
    )
  }

  private fun describeAlternativePaths(alternatives: List<RecordedFinding>): List<String> {
    val paths = alternatives.map { it.conditions }.distinct()
    val common = paths.first().filter { condition -> paths.all { condition in it } }
    val remaining = paths.map { it - common.toSet() }.distinct()
    if (remaining.any { it.isEmpty() }) return common.map { it.display }
    return common.map { it.display } +
      remaining.joinToString(" OR ") { path ->
        path.joinToString(" AND ", prefix = "(", postfix = ")") { it.display }
      }
  }

  private fun unknownCurrent(context: EvaluationContext): CanvasState.Unknown {
    val site = rootSite()
    addUnknown(context, "current-canvas", site, "No current Mosaic Canvas is available")
    return CanvasState.Unknown("No current Mosaic Canvas is available", site)
  }

  private fun restoreCaller(
    nested: EvaluationContext,
    caller: EvaluationContext,
    propagateBlocked: Boolean = true,
  ): EvaluationContext =
    caller.copy(
      aliases = if (nested.activation == caller.activation) nested.aliases else caller.aliases,
      assignments = nested.assignments,
      conditions = nested.conditions,
      feasibility = nested.feasibility,
      blocked = caller.blocked || propagateBlocked && nested.blocked,
      cycleProofSupported = caller.cycleProofSupported && (!propagateBlocked || nested.cycleProofSupported),
    )

  private fun joinEffectContinuations(outcomes: List<EvaluationContext>): List<EvaluationContext> {
    var joined = outcomes
    do {
      val previous = joined
      joined =
        previous.map { context ->
          if (context.blocked) return@map context
          val removal =
            context.conditions.asSequence().filter { it.kind == PathConditionKind.OPAQUE_ALTERNATIVE }.map {
                condition ->
              condition to
                previous.filter { other ->
                  !other.blocked && other.values == context.values && other.currentCanvas == context.currentCanvas &&
                    other.conditions.any { it.identity == condition.identity && it.value != condition.value } &&
                    other.conditions.filterNot { it.identity == condition.identity }.toSet() ==
                    context.conditions.filterNot { it.identity == condition.identity }.toSet()
                }
            }.firstOrNull { it.second.isNotEmpty() }
          val conditions = context.conditions - listOfNotNull(removal?.first).toSet()
          context.copy(
            conditions = conditions,
            // Joining availability alternatives cannot erase an unsupported execution step.
            cycleProofSupported =
              context.cycleProofSupported && removal?.second.orEmpty().all { it.cycleProofSupported },
            feasibility =
              if (conditions.any { it.kind == PathConditionKind.OPAQUE_ALTERNATIVE }) {
                PathFeasibility.OPAQUE
              } else {
                PathFeasibility.SUPPORTED
              },
          )
        }
    } while (joined != previous)
    // Assignments remain available for immutable Boolean correlation. Aliases retain
    // their own value conditions and reapply them when the Canvas is read.
    return joined
  }

  private fun rootSite(): SourceLocation = root.site ?: SourceLocation(root.target, "<contract>", 1, 1)

  private fun layerInstanceId(
    expression: CanvasExpression.Layer,
    context: EvaluationContext,
  ): String {
    val allocation = nextCanvasInstance++
    return buildString {
      append(context.scope).append(':').append(expression.id)
      context.dependencyPath.forEach { append('/').append(it.label).append('@').append(it.site.owner) }
      append('#').append(allocation)
    }
  }

  private fun combineFeasibility(
    first: PathFeasibility,
    second: PathFeasibility,
  ): PathFeasibility =
    if (first == PathFeasibility.OPAQUE || second == PathFeasibility.OPAQUE) {
      PathFeasibility.OPAQUE
    } else {
      PathFeasibility.SUPPORTED
    }
}
