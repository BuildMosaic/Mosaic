@file:Suppress(
  "ComplexCondition",
  "CyclomaticComplexMethod",
  "LargeClass",
  "LongMethod",
  "MaxLineLength",
  "NestedBlockDepth",
)

package org.buildmosaic.analysis

/** Selects execution contexts from complete, selected contracts. It never examines names or source files. */
object RootSelectionResolver {
  fun resolve(
    program: ModuleContract,
    selectedDependencies: List<ModuleContract>,
    explicitRoots: List<String>,
  ): List<SelectedRoot> {
    val declarations =
      (listOf(program) + selectedDependencies).flatMap { module ->
        module.callables.map { it.id to Contract(module.id, it.parameters, shape(it.effects)) } +
          module.canvases.map { it.id to Contract(module.id, it.parameters, shape(it.result)) }
      }
    if (explicitRoots.isNotEmpty()) {
      return explicitRoots.map { id ->
        require(declarations.any { it.first == id }) {
          "Selected root '$id' does not resolve to a callable or Canvas contract in the selected Mosaic contracts"
        }
        SelectedRoot(id, id)
      }
    }
    val conflicts = declarations.groupBy { it.first }.filterValues { it.size != 1 }.keys.sorted()
    require(conflicts.isEmpty()) {
      "Automatic Mosaic root discovery cannot resolve conflicting selected contracts: ${conflicts.joinToString()}"
    }
    val overrides = (listOf(program) + selectedDependencies).flatMap { it.overrides }
    val overrideConflicts = overrides.groupBy { it.receiverType to it.baseId }.filterValues { it.size != 1 }.keys
    require(overrideConflicts.isEmpty()) {
      val labels =
        overrideConflicts.sortedWith(compareBy({ it.first }, { it.second }))
          .joinToString { "${it.first} at ${it.second}" }
      "Automatic Mosaic root discovery cannot resolve conflicting direct overrides: $labels"
    }
    return Discovery(program.id, declarations.toMap(), overrides).roots()
  }

  private data class Contract(
    val owner: String,
    val parameters: List<ContractParameter>,
    val shape: Shape,
  )

  private data class Call(
    val target: String,
    val receiver: DispatchReceiver,
    val virtual: Boolean,
  )

  private class Shape {
    var composes = false
    val calls = mutableListOf<Call>()

    fun effects(effects: List<Effect>) {
      effects.forEach { effect ->
        when (effect) {
          is Effect.Compose -> {
            composes = true
            expression(effect.canvas)
          }
          is Effect.Lookup -> expression(effect.canvas)
          is Effect.ConstructCanvas -> expression(effect.canvas)
          is Effect.EstablishMosaic -> expression(effect.canvas)
          is Effect.Call -> {
            calls += Call(effect.target, effect.receiver, effect.virtualDispatch)
            arguments(effect.arguments)
          }
          is Effect.Branch -> {
            effects(effect.whenTrue)
            effects(effect.whenFalse)
          }
          is Effect.Captured -> effects(effect.effects)
          is Effect.Unknown -> Unit
        }
      }
    }

    fun expression(expression: CanvasExpression) {
      when (expression) {
        CanvasExpression.Empty, CanvasExpression.Current, is CanvasExpression.ParameterValue,
        is CanvasExpression.ValueReference, is CanvasExpression.Assumption, is CanvasExpression.Unknown,
        -> Unit
        is CanvasExpression.Layer -> {
          expression(expression.parent)
          expression.bindings.forEach { effects(it.constructorEffects) }
        }
        is CanvasExpression.Choice -> {
          expression(expression.whenTrue)
          expression(expression.whenFalse)
        }
        is CanvasExpression.RuntimeCall -> {
          effects(expression.callerEffects)
          calls += Call(expression.target, expression.receiver, expression.virtualDispatch)
          arguments(expression.arguments)
        }
        is CanvasExpression.WithEffects -> {
          effects(expression.effects)
          expression(expression.result)
        }
        is CanvasExpression.Captured -> expression(expression.expression)
        is CanvasExpression.Alias -> expression(expression.expression)
      }
    }

    private fun arguments(arguments: CallArguments) {
      arguments.values.values.forEach { argument ->
        if (argument is ArgumentExpression.Canvas) expression(argument.expression)
      }
    }
  }

  private fun shape(effects: List<Effect>) = Shape().apply { effects(effects) }

  private fun shape(expression: CanvasExpression) = Shape().apply { expression(expression) }

  private data class Activation(val id: String, val receiver: String?) {
    fun root() =
      SelectedRoot(
        if (receiver == null) id else "$id @ $receiver",
        id,
        receiverType = receiver,
        selection = RootSelection.AUTOMATIC,
      )

    fun label() = if (receiver == null) id else "$id on $receiver"
  }

  private class Discovery(
    private val localOwner: String,
    private val contracts: Map<String, Contract>,
    private val overrides: List<ResolvedOverride>,
  ) {
    private val receiverTypes =
      (
        overrides.map { it.receiverType } +
          contracts.values.flatMap { contract ->
            contract.shape.calls.mapNotNull { (it.receiver as? DispatchReceiver.Concrete)?.type }
          }
      ).distinct().sorted()
    private val overrideImplementations = overrides.map { it.implementationId }.toSet()
    private val sensitive = receiverSensitiveContracts()
    private val nodes =
      contracts.keys.flatMap { id ->
        val receivers = if (id in sensitive) listOf(null) + receiverTypes else listOf(null)
        receivers.map { Activation(id, it) }
      }.toSet()
    private val outgoing =
      nodes.associateWith { activation ->
        contracts.getValue(activation.id).shape.calls.mapNotNull { call -> target(activation, call) }.toSet()
      }
    private val incoming =
      nodes.associateWith { mutableSetOf<Activation>() }.also { index ->
        outgoing.forEach { (caller, callees) -> callees.forEach { index[it]?.add(caller) } }
      }

    fun roots(): List<SelectedRoot> {
      val anchors = anchoredTemplates()
      val eligible = mutableSetOf<Activation>()
      contracts.filterValues { it.owner == localOwner }.keys.forEach { eligible += Activation(it, null) }
      val pending = ArrayDeque(anchors)
      while (pending.isNotEmpty()) {
        val activation = pending.removeFirst()
        if (eligible.add(activation)) incoming[activation].orEmpty().forEach(pending::addLast)
      }
      val relevant = relevantActivations()
      val selected = eligible.filter { it in relevant && safe(it) && incoming[it].isNullOrEmpty() }.toSet()
      val covered = reachable(selected)
      val expected = localExecutionContexts(eligible).filter { it in relevant }
      val uncovered = expected.filterNot { it in covered }
      require(selected.isNotEmpty() && uncovered.isEmpty()) {
        val detail = uncovered.map(Activation::label).sorted().joinToString().ifEmpty { "none" }
        "Automatic Mosaic root discovery found no safe outermost execution context for: $detail. " +
          "Add a selected-contract caller that supplies the Canvas and receiver, " +
          "or configure mosaicAnalysis.roots explicitly."
      }
      return selected.map(Activation::root).sortedBy { it.id }
    }

    private fun localExecutionContexts(eligible: Set<Activation>): Set<Activation> {
      val reachable = reachable(eligible)
      return contracts.filterValues { it.owner == localOwner }.keys.flatMap { id ->
        if (id !in sensitive) {
          listOf(Activation(id, null))
        } else {
          reachable.filter { it.id == id && it.receiver != null }.ifEmpty { listOf(Activation(id, null)) }
        }
      }.toSet()
    }

    private fun anchoredTemplates(): Set<Activation> =
      overrides.filter { contracts[it.implementationId]?.owner == localOwner }.flatMap { override ->
        contracts.filterValues { contract ->
          contract.shape.calls.any { call ->
            call.virtual && call.receiver == DispatchReceiver.Forwarded && call.target == override.baseId
          }
        }.keys.map { Activation(it, override.receiverType) }
      }.filter { it in nodes }.toSet()

    private fun safe(activation: Activation): Boolean {
      val contract = contracts.getValue(activation.id)
      val noCanvasInput = contract.parameters.none { it.kind == ParameterKind.CANVAS }
      return noCanvasInput && activation.id !in overrideImplementations &&
        (activation.id !in sensitive || activation.receiver != null)
    }

    private fun relevantActivations(): Set<Activation> {
      val relevant = nodes.filterTo(mutableSetOf()) { contracts.getValue(it.id).shape.composes }
      var changed: Boolean
      do {
        changed = false
        nodes.forEach { activation ->
          if (activation !in relevant && outgoing[activation].orEmpty().any { it in relevant }) {
            relevant += activation
            changed = true
          }
        }
      } while (changed)
      return relevant
    }

    private fun reachable(starts: Set<Activation>): Set<Activation> {
      val visited = mutableSetOf<Activation>()
      val pending = ArrayDeque(starts)
      while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (visited.add(next)) outgoing[next].orEmpty().forEach(pending::addLast)
      }
      return visited
    }

    private fun target(
      caller: Activation,
      call: Call,
    ): Activation? {
      val receiver =
        when (val dispatch = call.receiver) {
          DispatchReceiver.None, is DispatchReceiver.Unknown -> null
          DispatchReceiver.Forwarded -> caller.receiver
          is DispatchReceiver.Concrete -> dispatch.type
        }
      val id =
        if (call.virtual) {
          if (receiver == null) return null
          overrides.singleOrNull { it.baseId == call.target && it.receiverType == receiver }?.implementationId
            ?: return null
        } else {
          call.target
        }
      if (id !in contracts) return null
      return Activation(id, if (id in sensitive) receiver else null)
    }

    private fun receiverSensitiveContracts(): Set<String> {
      val result = mutableSetOf<String>()
      var changed: Boolean
      do {
        changed = false
        contracts.forEach { (id, contract) ->
          if (id !in result &&
            contract.shape.calls.any { call ->
              call.receiver == DispatchReceiver.Forwarded && (call.virtual || call.target in result)
            }
          ) {
            result += id
            changed = true
          }
        }
      } while (changed)
      return result
    }
  }
}
