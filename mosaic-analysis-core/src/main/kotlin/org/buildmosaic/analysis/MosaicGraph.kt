@file:Suppress("CyclomaticComplexMethod", "LargeClass", "LongMethod", "LongParameterList", "TooManyFunctions")

package org.buildmosaic.analysis

private const val MAX_CANVAS_EXPRESSION_DEPTH = 64

/** Documentation of selected contracts. Graph topology is independent of evaluation findings. */
object MosaicGraph {
  fun render(request: AnalysisRequest): String {
    val report = if (request.roots.isEmpty()) null else MosaicAnalyzer().analyze(request)
    val overview = Graph(request.program, request.selectedDependencies)
    request.program.tiles.sortedBy { it.id }.forEach { overview.tile(it.id) }
    request.program.canvases.sortedBy { it.id }.forEach { overview.canvas(it.id) }
    val relevantCallables = overview.relevantCallableIds()
    request.program.callables.sortedBy { it.id }.forEach { callable ->
      if (callable.id in relevantCallables) overview.callable(callable.id)
    }
    request.roots.map { it.target }.distinct().sorted().forEach(overview::root)
    return buildString {
      appendLine("# Mosaic dependency graph")
      appendLine()
      appendLine("## Local contract overview")
      appendLine()
      appendLine(
        "Structure from the local summary and referenced selected dependency contracts. No verification is implied.",
      )
      appendLine()
      appendLine(
        "Legend: Tile and MultiTile are work units; Canvas layers contain bindings. " +
          "Arrows show composition, calls, Canvas use, and requirements. " +
          "Possible override arrows show selected dispatch candidates, not executed paths. " +
          "Unknown nodes mark unresolved contracts or facts.",
      )
      appendLine()
      append(overview.render())
      report?.roots?.forEach { root ->
        val graph = Graph(request.program, request.selectedDependencies)
        graph.root(root.root.target)
        appendLine()
        appendLine("## Root: ${heading(root.root.id)}")
        appendLine()
        appendLine("Selection: ${root.root.selection}; receiver: ${heading(root.root.receiverType ?: "none")}")
        appendLine()
        appendLine("Status: ${root.status}")
        appendLine()
        append(graph.render())
        appendLine()
        appendLine("### Findings for ${heading(root.root.id)}")
        appendLine()
        if (root.findings.isEmpty()) {
          appendLine("No findings reported for this selected root.")
        } else {
          appendLine(
            "| Certainty | Kind | Obligation | Key | Site | Canvas path | Dependency path | " +
              "Condition | Binding site | Provider key source | Detail |",
          )
          appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
          root.findings.sortedWith(
            compareBy({ it.obligationId }, { it.site.path }, { it.site.line }, { it.reason }),
          ).forEach {
              finding ->
            appendLine(
              listOf(
                finding.certainty, finding.kind, finding.obligationId,
                finding.key?.let(::keyLabel).orEmpty(), siteLabel(finding.site),
                finding.canvasPath.joinToString(" → ") { it.layerId },
                finding.dependencyPath.joinToString(" → ") { it.label },
                finding.pathCondition.joinToString(
                  " ∧ ",
                ),
                finding.bindingSite?.let(::siteLabel).orEmpty(),
                finding.bindingFactProvenance?.let(::siteLabel).orEmpty(),
                finding.rule?.let {
                  "${it.id}: ${if (finding.suppressedAt.isNotEmpty()) "SUPPRESSED" else finding.severity}; "
                }.orEmpty() +
                  finding.reason + finding.suppressedAt.joinToString(prefix = " ") { "Suppressed at ${siteLabel(it)}" },
              ).joinToString(" | ", prefix = "| ", postfix = " |") { cell(it.toString()) },
            )
          }
        }
      }
    }
  }

  private fun keyLabel(key: CanvasKeyIdentity) = key.classId + (key.qualifier?.let { " [$it]" } ?: "")

  private fun siteLabel(site: SourceLocation) = "${site.path}:${site.line}"

  private fun heading(value: String) =
    value.replace("&", "&amp;").replace("<", "&lt;")
      .replace(">", "&gt;").replace("`", "&#96;").replace("\n", " ")

  private fun cell(value: String) = value.replace("|", "\\|").replace("\n", " ").replace("\r", " ")

  private class Graph(local: ModuleContract, dependencies: List<ModuleContract>) {
    private val localId = local.id
    private val modules = listOf(local) + dependencies
    private val tiles = modules.flatMap { module -> module.tiles.map { it.id to (module.id to it) } }.toMap()
    private val canvases = modules.flatMap { module -> module.canvases.map { it.id to (module.id to it) } }.toMap()
    private val callables = modules.flatMap { module -> module.callables.map { it.id to (module.id to it) } }.toMap()
    private val overrides = modules.flatMap { it.overrides }
    private val registry = SelectedContractRegistry(modules)
    private val nodes = sortedMapOf<String, String>()
    private val edges =
      sortedSetOf<Triple<String, String, String>>(compareBy({ it.first }, { it.second }, { it.third }))
    private val visited = mutableSetOf<String>()

    /** A fixed point includes callers of relevant helpers without seeding opaque-only contracts. */
    fun relevantCallableIds(): Set<String> {
      val relevant = mutableSetOf<String>()
      var changed: Boolean
      do {
        changed = false
        callables.keys.sorted().forEach { id ->
          if (id !in relevant && callables.getValue(id).second.effects.any { relevant(it, relevant) }) {
            relevant.add(id)
            changed = true
          }
        }
      } while (changed)
      return relevant
    }

    private fun relevant(
      effect: Effect,
      known: Set<String>,
    ): Boolean =
      when (effect) {
        is Effect.Lookup, is Effect.Compose, is Effect.ConstructCanvas, is Effect.EstablishMosaic -> true
        is Effect.Call ->
          callTargets(effect.target, effect.receiver, effect.virtualDispatch).any { it in canvases || it in known } ||
            effect.arguments.values.values.any { argument ->
              argument is ArgumentExpression.Canvas && relevant(argument.expression, known)
            }
        is Effect.Branch -> (effect.whenTrue + effect.whenFalse).any { relevant(it, known) }
        is Effect.Captured -> effect.effects.any { relevant(it, known) }
        is Effect.Unknown -> false
      }

    private fun callTargets(
      target: String,
      receiver: DispatchReceiver,
      virtualDispatch: Boolean,
    ): List<String> =
      if (!virtualDispatch) {
        listOf(target)
      } else {
        overrides.filter { override ->
          override.baseId == target &&
            (receiver !is DispatchReceiver.Concrete || override.receiverType == receiver.type)
        }.map { it.implementationId }
      }

    private fun relevant(
      expression: CanvasExpression,
      known: Set<String>,
    ): Boolean =
      when (expression) {
        CanvasExpression.Empty, CanvasExpression.Current, is CanvasExpression.ParameterValue -> false
        is CanvasExpression.Layer, is CanvasExpression.RuntimeCall, is CanvasExpression.ValueReference,
        is CanvasExpression.Assumption, is CanvasExpression.Unknown,
        -> true
        is CanvasExpression.Choice -> relevant(expression.whenTrue, known) || relevant(expression.whenFalse, known)
        is CanvasExpression.WithEffects ->
          expression.effects.any { relevant(it, known) } || relevant(expression.result, known)
        is CanvasExpression.Captured -> relevant(expression.expression, known)
        is CanvasExpression.Alias -> relevant(expression.expression, known)
      }

    fun root(id: String) {
      when {
        id in callables -> callable(id)
        id in canvases -> canvas(id)
        else -> node("unknown:$id", "Unresolved root: $id")
      }
    }

    fun tile(id: String) {
      val pair = tiles[id]
      if (pair == null) {
        node("tile:$id", "Unknown Tile: $id")
        return
      }
      val (owner, contract) = pair
      val n = "tile:$id"
      node(n, "${if (contract.multi) "MultiTile" else "Tile"}: $id${origin(owner)}")
      if (!visited.add(n)) return
      effects(n, contract.effects)
    }

    fun canvas(id: String) {
      val pair = canvases[id]
      if (pair == null) {
        node("canvas:$id", "Unknown Canvas: $id")
        return
      }
      val (owner, contract) = pair
      val n = "canvas:$id"
      node(n, "Canvas: $id${origin(owner)}")
      if (!visited.add(n)) return
      expression(n, contract.result, "result")
    }

    fun callable(id: String) {
      val pair = callables[id]
      if (pair == null) {
        node("call:$id", "Unknown callable: $id")
        return
      }
      val (owner, contract) = pair
      val n = "call:$id"
      node(n, "Callable: $id${origin(owner)}")
      if (!visited.add(n)) return
      effects(n, contract.effects)
    }

    private fun origin(owner: String) = if (owner == localId) "" else " [dependency: $owner]"

    private fun node(
      id: String,
      label: String,
    ) {
      nodes[id] = label
    }

    private fun edge(
      from: String,
      to: String,
      label: String,
    ) {
      edges.add(Triple(from, to, label))
    }

    private fun detail(
      parent: String,
      id: String,
      label: String,
      relation: String,
    ) {
      node(id, label)
      edge(parent, id, relation)
    }

    private fun effects(
      parent: String,
      effects: List<Effect>,
      context: String = "",
    ) {
      effects.forEachIndexed { index, effect ->
        val n = "$parent/effect:$context:$index:${effect.id}"
        when (effect) {
          is Effect.Lookup -> {
            detail(parent, n, "${effect.kind} lookup: ${factLabel(effect.key)}", "requires")
            expression(n, effect.canvas, "Canvas")
          }
          is Effect.Compose -> {
            val execution =
              if (effect.execution != MultiTileExecution.NOT_APPLICABLE) " / ${effect.execution}" else ""
            val label = "${effect.discovery}$execution"
            detail(parent, n, "Compose: ${effect.id} ($label)", "compose")
            edge(n, tileTarget(effect.tile), "Tile")
            expression(n, effect.canvas, "Canvas")
          }
          is Effect.EstablishMosaic -> {
            detail(parent, n, "Fresh Mosaic: ${effect.id}", "create Mosaic")
            expression(n, effect.canvas, "Canvas")
          }
          is Effect.ConstructCanvas -> {
            val value = "value:${effect.id}"
            node(value, "Canvas value: ${effect.id}")
            edge(parent, value, "construct Canvas")
            expression(value, effect.canvas, "value")
          }
          is Effect.Call -> {
            val contextual = effect.arguments.hasCanvas()
            if (!effect.virtualDispatch && contextual) {
              detail(parent, n, "Call: ${effect.id} (${effect.target})", "call")
              contractEdge(n, effect.target, false, "target")
              arguments(n, effect.arguments)
            } else {
              val harmless = !effect.virtualDispatch && callables[effect.target]?.second?.effects?.isEmpty() == true
              if (!harmless) call(parent, n, effect.target, effect.receiver, effect.virtualDispatch, false, "call")
              arguments(if (effect.virtualDispatch) n else parent, effect.arguments)
            }
          }
          is Effect.Branch -> {
            detail(parent, n, "Condition: ${guardLabel(effect.guard)}", "branch")
            val trueArm = "$n/true"
            val falseArm = "$n/false"
            detail(n, trueArm, "When true", "true")
            detail(n, falseArm, "When false", "false")
            effects(trueArm, effect.whenTrue)
            effects(falseArm, effect.whenFalse)
          }
          is Effect.Captured -> {
            detail(
              parent,
              n,
              "Captured: ${effect.owner}${if (effect.faithfullyCaptured) "" else " (unverified capture)"}",
              "capture",
            )
            effects(n, effect.effects)
          }
          is Effect.Unknown -> detail(parent, n, "Unknown: ${effect.reason}", "unknown")
        }
      }
    }

    private fun tileTarget(reference: TileReference): String =
      when (reference) {
        is TileReference.Stable -> "tile:${reference.contractId}".also { tile(reference.contractId) }
        is TileReference.ExportedProperty -> "tile:${reference.contractId}".also { tile(reference.contractId) }
        is TileReference.Alias -> tileTarget(reference.reference)
        is TileReference.Fresh -> "tile:${reference.templateId}".also { tile(reference.templateId) }
        is TileReference.Unknown ->
          "unknown-tile:${reference.site.path}:${reference.site.line}:${reference.reason}".also {
            node(it, "Unknown Tile: ${reference.reason}")
          }
      }

    private fun call(
      parent: String,
      siteNode: String,
      target: String,
      receiver: DispatchReceiver,
      virtualDispatch: Boolean,
      canvasResult: Boolean,
      relation: String,
    ) {
      if (!virtualDispatch) {
        contractEdge(parent, target, canvasResult, relation)
        return
      }
      detail(parent, siteNode, "Virtual call: $target", relation)
      val candidates =
        overrides.filter { override ->
          override.baseId == target &&
            (receiver !is DispatchReceiver.Concrete || override.receiverType == receiver.type)
        }.sortedWith(compareBy({ it.receiverType }, { it.implementationId }))
      candidates.forEach { override ->
        contractEdge(
          siteNode,
          override.implementationId,
          canvasResult,
          "possible override (${override.receiverType})",
        )
      }
      if (receiver is DispatchReceiver.Forwarded) {
        detail(
          siteNode,
          "$siteNode/context",
          "Receiver forwarded by caller at $target; selected implementation depends on caller context",
          "context dependent",
        )
      } else if (receiver !is DispatchReceiver.Concrete || candidates.size != 1) {
        val reason =
          when (receiver) {
            is DispatchReceiver.Concrete ->
              if (candidates.isEmpty()) {
                "No selected override for ${receiver.type} at $target"
              } else {
                "Conflicting overrides for ${receiver.type} at $target"
              }
            DispatchReceiver.Forwarded -> error("handled above")
            is DispatchReceiver.Unknown -> "Unknown receiver at $target: ${receiver.reason}"
            DispatchReceiver.None -> "Virtual receiver unresolved at $target"
          }
        detail(siteNode, "$siteNode/unresolved", "Unknown dispatch: $reason", "unresolved")
      }
    }

    private fun contractEdge(
      parent: String,
      target: String,
      canvasResult: Boolean,
      relation: String,
    ) {
      val id = if (canvasResult) "canvas:$target" else "call:$target"
      edge(parent, id, relation)
      if (canvasResult) canvas(target) else callable(target)
    }

    private fun expression(
      parent: String,
      expression: CanvasExpression,
      relation: String,
      depth: Int = 0,
    ) {
      if (depth > MAX_CANVAS_EXPRESSION_DEPTH) {
        detail(parent, "$parent/depth", "Unknown: Canvas expression depth limit", relation)
        return
      }
      val n = "$parent/canvas:$relation:$depth"
      when (expression) {
        CanvasExpression.Empty -> Unit
        CanvasExpression.Current -> Unit
        is CanvasExpression.ParameterValue ->
          detail(
            parent,
            n,
            "Canvas parameter: ${expression.parameter.name}",
            relation,
          )
        is CanvasExpression.Layer -> {
          detail(parent, n, "Canvas layer: ${expression.id}", relation)
          expression(n, expression.parent, "parent", depth + 1)
          expression.bindings.forEachIndexed { index, binding ->
            val b = "$n/binding:$index"
            detail(n, b, "Binding: ${factLabel(binding.key)}", "provides")
            effects(b, binding.constructorEffects)
          }
          expression.unknownRegistrations.forEachIndexed { index, unknown ->
            detail(n, "$n/unknown:$index", "Unknown registration: ${unknown.reason}", "registration")
          }
        }
        is CanvasExpression.Choice -> {
          detail(parent, n, "Canvas choice: ${guardLabel(expression.guard)}", relation)
          expression(n, expression.whenTrue, "true", depth + 1)
          expression(n, expression.whenFalse, "false", depth + 1)
        }
        is CanvasExpression.RuntimeCall -> {
          val contextual = expression.arguments.hasCanvas() || expression.callerEffects.isNotEmpty()
          if (!expression.virtualDispatch && contextual) {
            val site = expression.site
            val invocation = "$n/runtime:${expression.target}:${site.path}:${site.line}:${site.column}"
            detail(parent, invocation, "Canvas call: ${expression.target}", relation)
            contractEdge(invocation, expression.target, true, "returns Canvas")
            arguments(invocation, expression.arguments)
            effects(invocation, expression.callerEffects)
          } else {
            call(parent, n, expression.target, expression.receiver, expression.virtualDispatch, true, relation)
            arguments(if (expression.virtualDispatch) n else parent, expression.arguments)
            effects(if (expression.virtualDispatch) n else parent, expression.callerEffects)
          }
        }
        is CanvasExpression.ValueReference -> {
          val value = "value:${expression.id}"
          node(value, "Canvas value: ${expression.id}")
          edge(parent, value, relation)
        }
        is CanvasExpression.WithEffects -> {
          effects(parent, expression.effects)
          expression(parent, expression.result, relation, depth + 1)
        }
        is CanvasExpression.Captured -> {
          detail(parent, n, "Captured Canvas: ${expression.owner}", relation)
          expression(n, expression.expression, "value", depth + 1)
        }
        is CanvasExpression.Assumption -> detail(parent, n, "Assumed Canvas: ${expression.id}", relation)
        is CanvasExpression.Alias -> expression(parent, expression.expression, relation, depth + 1)
        is CanvasExpression.Unknown -> detail(parent, n, "Unknown Canvas: ${expression.reason}", relation)
      }
    }

    private fun arguments(
      parent: String,
      arguments: CallArguments,
    ) {
      arguments.values.entries.sortedBy { it.key.name }.forEach { (parameter, value) ->
        if (value is ArgumentExpression.Canvas) expression(parent, value.expression, "argument ${parameter.name}")
      }
    }

    private fun CallArguments.hasCanvas(): Boolean = values.values.any { it is ArgumentExpression.Canvas }

    private fun factLabel(fact: Fact<CanvasKeyIdentity>): String =
      when (fact) {
        is Fact.Known -> keyLabel(fact.value)
        is Fact.ExportedKey ->
          when (val resolved = registry.key(fact.declaration)) {
            is Resolution.Found -> keyLabel(resolved.value.key)
            is Resolution.Conflict -> "unknown (conflicting exported key ${fact.declaration})"
            Resolution.Missing -> "unknown (unresolved exported key ${fact.declaration})"
          }
        is Fact.Unknown -> "unknown (${fact.reason})"
      }

    private fun guardLabel(guard: Guard): String =
      when (guard) {
        is Guard.Constant -> guard.value.toString()
        is Guard.BooleanParameter -> "${guard.parameter.name} = ${guard.expected}"
        is Guard.Opaque -> "unknown (${guard.reason})"
      }

    fun render(): String =
      buildString {
        appendLine("```mermaid")
        appendLine("flowchart LR")
        val ids = nodes.keys.withIndex().associate { (i, id) -> id to "n$i" }
        nodes.forEach { (id, label) -> appendLine("  ${ids.getValue(id)}[\"${mermaid(label)}\"]") }
        edges.forEach { (from, to, label) ->
          appendLine("  ${ids.getValue(from)} -->|\"${mermaid(label)}\"| ${ids.getValue(to)}")
        }
        appendLine("```")
      }

    private fun mermaid(value: String) =
      value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ").replace("|", "&#124;")
        .replace("`", "&#96;")
  }
}
