@file:Suppress(
  "CyclomaticComplexMethod",
  "LongMethod",
  "TooManyFunctions",
  "NestedBlockDepth",
  "LargeClass",
  "MaxLineLength",
  "ktlint:standard:max-line-length",
)

package org.buildmosaic.compiler

import org.buildmosaic.analysis.ArgumentExpression
import org.buildmosaic.analysis.Binding
import org.buildmosaic.analysis.CallArguments
import org.buildmosaic.analysis.CallableContract
import org.buildmosaic.analysis.CanvasContract
import org.buildmosaic.analysis.CanvasExpression
import org.buildmosaic.analysis.CanvasKeyIdentity
import org.buildmosaic.analysis.ContractParameter
import org.buildmosaic.analysis.DiscoveryKind
import org.buildmosaic.analysis.DispatchReceiver
import org.buildmosaic.analysis.Effect
import org.buildmosaic.analysis.Fact
import org.buildmosaic.analysis.KeyContract
import org.buildmosaic.analysis.LookupKind
import org.buildmosaic.analysis.ModuleContract
import org.buildmosaic.analysis.MosaicProvenance
import org.buildmosaic.analysis.MosaicRule
import org.buildmosaic.analysis.MosaicSuppression
import org.buildmosaic.analysis.MultiTileExecution
import org.buildmosaic.analysis.OverrideSlot
import org.buildmosaic.analysis.ParameterKind
import org.buildmosaic.analysis.ResolvedOverride
import org.buildmosaic.analysis.SourceLocation
import org.buildmosaic.analysis.SourceShard
import org.buildmosaic.analysis.TileContract
import org.buildmosaic.analysis.TileReference
import org.buildmosaic.analysis.UnknownRegistration
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLocalDelegatedPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/** Collects one source file and owns all mutable IR identity and contract state for that file. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class SourceContractExtractor(
  private val moduleId: String,
  private val sourceRoot: String?,
  private val coreAnalysisRevision: Int,
) {
  private lateinit var locations: SourceLocations
  private val limitations = mutableListOf<String>()
  private val freshTemplates = linkedMapOf<String, TileContract>()
  private val localTileDelegates =
    mutableMapOf<org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol, org.jetbrains.kotlin.ir.symbols.IrValueSymbol>()

  fun extract(file: IrFile): SourceShard {
    locations = SourceLocations(file)
    val canvases = mutableListOf<CanvasContract>()
    val tiles = mutableListOf<TileContract>()
    val callables = mutableListOf<CallableContract>()
    val overrides = mutableListOf<ResolvedOverride>()
    val keys = mutableListOf<KeyContract>()
    val locators = linkedMapOf<String, String>()

    fun visit(
      declaration: IrDeclaration,
      inherited: List<MosaicSuppression>,
    ) {
      val suppressions = inherited + mosaicSuppressions(declaration.annotations, declaration)

      when (declaration) {
        is IrClass -> {
          val receiverType = declaration.fqNameWhenAvailable?.asString()
          if (declaration.modality == Modality.FINAL && receiverType != null) {
            declaration.declarations.filterIsInstance<IrSimpleFunction>().filterNot { it.isFakeOverride }.forEach {
                function ->
              function.overriddenSymbols.map { it.owner }.filter { base ->
                base.modality == Modality.ABSTRACT &&
                  base.isSuspend && function.isSuspend &&
                  base.parameters.any { it.kind == IrParameterKind.Regular && isCanvasType(it.type) }
              }.forEach {
                  base ->
                val baseId = symbolId(base)
                val implementationId = symbolId(function)
                val slots =
                  base.parameters.filter { it.kind == IrParameterKind.Regular }.zip(
                    function.parameters.filter { it.kind == IrParameterKind.Regular },
                  ).mapIndexedNotNull { position, (baseParameter, implementationParameter) ->
                    if (isCanvasType(baseParameter.type) && isCanvasType(implementationParameter.type)) {
                      OverrideSlot(
                        ContractParameter(baseId, baseParameter.name.asString(), ParameterKind.CANVAS),
                        ContractParameter(
                          implementationId,
                          implementationParameter.name.asString(),
                          ParameterKind.CANVAS,
                        ),
                        position,
                      )
                    } else {
                      null
                    }
                  }
                overrides += ResolvedOverride(receiverType, baseId, implementationId, slots)
              }
            }
          }
          declaration.declarations.forEach { visit(it, suppressions) }
        }
        is IrProperty -> {
          val initializer = declaration.backingField?.initializer?.expression
          stableKeyProperty(declaration)?.let { key ->
            keys += KeyContract(propertyId(declaration), key, location(declaration, propertyId(declaration)))
          }
          val stableTile = tilePropertyEligibility(declaration) == TilePropertyEligibility.LOCAL_STABLE
          if (stableTile) {
            val call = tileInitializer(declaration)!!
            val lambda = call.argument("block") ?: call.argument("fetch")
            val normalizer = Normalizer(file, propertyId(declaration), suppressionScope = suppressions)
            val function = lambdaFunction(lambda)
            function?.parameters?.filter { it.kind == IrParameterKind.ExtensionReceiver }?.forEach {
              normalizer.bindCurrentReceiver(it, mosaic = true)
            }
            val body = function?.body
            if (body != null) {
              normalizer.body(body)
            } else {
              normalizer.effects += Effect.Unknown("${propertyId(declaration)}:body", "Tile block is unavailable", location(declaration, propertyId(declaration)))
            }
            tiles +=
              TileContract(
                propertyId(declaration), normalizer.effects, location(declaration, propertyId(declaration)),
                suppressions = suppressions,
                multi =
                  call.symbol.owner.name.asString() !in setOf("singleTile", "<init>") ||
                    call.symbol.owner.parent.let {
                      it is IrClass && it.fqNameWhenAvailable?.asString() == "org.buildmosaic.core.MultiTile"
                    },
              )
            declaration.getter?.let { locators[propertyId(declaration)] = binaryLocator(it, file) }
          }
          listOfNotNull(declaration.getter, declaration.setter).forEach { accessor ->
            val id = symbolId(accessor)
            val site = location(accessor, id)
            val normalizer = Normalizer(file, id, collectFreshTemplates = !stableTile, suppressionScope = suppressions)
            val value =
              when {
                stableTile -> normalizer.body(tileInitializer(declaration))
                declaration.isDelegated -> {
                  normalizer.effects += Effect.Unknown("$id:delegated", "Unsupported delegated property initialization", site)
                  Value()
                }
                accessor.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR -> {
                  if (isCanvasType(accessor.returnType)) {
                    Value(canvas = CanvasExpression.Unknown("Stored Canvas property provenance is unavailable", site))
                  } else if (initializer != null) {
                    normalizer.body(initializer)
                  } else {
                    normalizer.effects += Effect.Unknown("$id:initialization", "Stored property initialization is unavailable", site)
                    Value()
                  }
                }
                accessor.body == null -> {
                  normalizer.effects += Effect.Unknown("$id:body", "Accessor body is unavailable", site)
                  Value()
                }
                else -> normalizer.body(accessor.body)
              }
            if (isCanvasType(accessor.returnType)) {
              canvases += CanvasContract(id, canvasParameters(accessor, id), normalizer.result(value, accessor), site)
            } else {
              callables += CallableContract(id, canvasParameters(accessor, id), normalizer.effects, site)
            }
            locators[id] = binaryLocator(accessor, file)
          }
        }
        is IrConstructor -> {
          val id = symbolId(declaration)
          val site = location(declaration, id)
          val normalizer = Normalizer(file, id, suppressionScope = suppressions)
          // Delegation is an invocation too. Class initialization is deliberately a conservative boundary.
          (declaration.body as? IrBlockBody)?.statements?.filterIsInstance<IrDelegatingConstructorCall>()?.forEach {
            locations.primaryDelegation(declaration, it)
            normalizer.evaluate(it)
          }
          val clazz = declaration.parent as? IrClass
          val initializers =
            clazz?.declarations.orEmpty().mapNotNull {
              when (it) {
                is IrAnonymousInitializer -> it.body
                is IrProperty -> it.backingField?.initializer
                else -> null
              }
            }
          val ownBody = (declaration.body as? IrBlockBody)?.statements.orEmpty().filterNot { it is IrDelegatingConstructorCall }
          if (declaration.body == null || initializers.any { !provenHarmless(it) } || ownBody.any { !provenHarmless(it) }) {
            normalizer.effects += Effect.Unknown("$id:initialization", "Constructor initialization may have Mosaic effects", site)
          }
          callables += CallableContract(id, canvasParameters(declaration, id), normalizer.effects, site)
          locators[id] = binaryLocator(declaration, file)
        }
        is IrSimpleFunction -> {
          if (declaration.isFakeOverride || declaration.correspondingPropertySymbol != null) return
          val id = symbolId(declaration)
          locators[id] = binaryLocator(declaration, file)
          val site = location(declaration, id)
          val normalizer = Normalizer(file, id, suppressionScope = suppressions)
          if (declaration.body == null) normalizer.effects += Effect.Unknown("$id:body", "Callable body is unavailable", site)
          val value = normalizer.body(declaration.body)
          if (isCanvasType(declaration.returnType)) {
            canvases += CanvasContract(id, canvasParameters(declaration, id), normalizer.result(value, declaration), site)
          } else {
            callables += CallableContract(id, canvasParameters(declaration, id), normalizer.effects, site)
          }
        }
      }
    }
    val fileSuppressions = mosaicSuppressions(file.annotations, file)
    file.declarations.forEach { visit(it, fileSuppressions) }
    tiles += freshTemplates.values
    val sourceId = SourceShardStorage.sourceIdentity(file, sourceRoot)
    val shard =
      SourceShard(
        sourceId,
        ModuleContract(moduleId, canvases, tiles, callables, overrides, keys),
        limitations.toList(),
        locators,
        coreAnalysisRevision,
      )
    return shard
  }

  private fun location(
    element: IrElement,
    owner: String,
  ) = locations.location(element, owner)

  private fun mosaicSuppressions(
    annotations: List<IrConstructorCall>,
    declaration: IrElement,
  ): List<MosaicSuppression> =
    annotations.filter {
      (it.symbol.owner.parent as? IrClass)?.fqNameWhenAvailable?.asString() == "kotlin.Suppress"
    }.flatMap { annotation ->
      annotation.arguments.filterIsInstance<IrVararg>().flatMap { it.elements }.filterIsInstance<IrConst>().mapNotNull {
        val id = it.value as? String
        if (MosaicRule.entries.any { rule -> rule.id == id && rule.suppressible }) {
          MosaicSuppression(id!!, location(declaration, "suppression:$id"))
        } else {
          null
        }
      }
    }

  /** Values carry references only. Evaluation owns effects; aliases and parameter binding never rescan IR. */
  private data class Value(
    val canvas: CanvasExpression? = null,
    val mosaic: CanvasExpression? = null,
    val mosaicIdentity: MosaicProvenance = MosaicProvenance.Unknown,
    val tile: TileReference? = null,
    val dispatch: DispatchReceiver = DispatchReceiver.None,
    val callable: Boolean = false,
    val builder: Boolean = false,
    val key: Fact<CanvasKeyIdentity>? = null,
    val keyClass: String? = null,
    val qualifier: Fact<String?>? = null,
  )

  private val identities = java.util.IdentityHashMap<IrElement, Int>()

  private fun identity(element: IrElement): Int = identities.getOrPut(element) { identities.size }

  private inner class Normalizer(
    val file: IrFile,
    val owner: String,
    val values: MutableMap<org.jetbrains.kotlin.ir.symbols.IrValueSymbol, Value> = mutableMapOf(),
    val collectFreshTemplates: Boolean = true,
    var suppressionScope: List<MosaicSuppression> = emptyList(),
  ) {
    val effects = mutableListOf<Effect>()

    private fun site(element: IrElement) = location(element, owner)

    private fun valueId(element: IrElement) = "$owner:value:${identity(element)}"

    private fun unknown(
      element: IrElement,
      reason: String,
    ) {
      effects += Effect.Unknown(valueId(element), reason, site(element))
    }

    private fun canvas(
      value: Value,
      element: IrElement,
    ): CanvasExpression = value.canvas ?: CanvasExpression.Unknown("Unsupported Canvas provenance", site(element))

    private fun materialize(
      element: IrElement,
      expression: CanvasExpression,
    ): Value {
      val slot = valueId(element)
      effects += Effect.ConstructCanvas(slot, CanvasExpression.Alias(slot, expression), site(element))
      return Value(canvas = CanvasExpression.ValueReference(slot, site(element)))
    }

    /** Snapshot the receiver at DSL entry; captured values must not follow a nested currentCanvas. */
    fun bindCurrentReceiver(
      parameter: IrValueParameter,
      mosaic: Boolean,
    ) {
      val receiver = materialize(parameter, CanvasExpression.Current).canvas
      values[parameter.symbol] = if (mosaic) Value(mosaic = receiver, mosaicIdentity = MosaicProvenance.Current) else Value(canvas = receiver)
    }

    fun body(element: IrElement?): Value =
      if (element == null) {
        Value()
      } else {
        evaluate(element)
      }

    fun result(
      value: Value,
      element: IrElement,
    ): CanvasExpression = CanvasExpression.WithEffects(effects.toList(), canvas(value, element))

    private fun sequence(statements: List<IrElement>): Value {
      var result = Value()
      for ((index, statement) in statements.withIndex()) {
        if (statement is IrReturn && index != statements.lastIndex) {
          unknown(statement, "Unsupported early return control flow")
          return Value()
        }
        result = evaluate(statement)
      }
      return result
    }

    fun evaluate(element: IrElement): Value {
      val result =
        when (element) {
          is IrBlockBody -> sequence(element.statements)
          is IrBlock -> sequence(element.statements)
          is IrExpressionBody -> evaluate(element.expression)
          is IrReturn -> evaluate(element.value)
          is IrVariable -> {
            val previousScope = suppressionScope
            suppressionScope = previousScope + mosaicSuppressions(element.annotations, element)
            val value = element.initializer?.let(::evaluate) ?: Value()
            suppressionScope = previousScope
            if (!element.isVar) {
              values[element.symbol] = value
            } else if (isCapabilityType(element.type)) {
              unknown(element, "Unsupported mutable capability provenance")
            }
            Value()
          }
          is IrLocalDelegatedProperty -> {
            val previousScope = suppressionScope
            suppressionScope = previousScope + mosaicSuppressions(element.annotations, element)
            val delegate = localTileDelegate(element)
            if (delegate != null) {
              evaluate(delegate)
              localTileDelegates[element.getter.symbol] = delegate.symbol
            } else {
              element.delegate?.initializer?.let(::evaluate)
              unknown(element, "Unsupported local delegated property initialization")
            }
            suppressionScope = previousScope
            Value()
          }
          is IrGetValue -> values[element.symbol] ?: parameterValue(element)
          is IrTypeOperatorCall -> evaluate(element.argument)
          is IrConst ->
            if (element.type.classFqName?.asString() == "kotlin.String" || element.value == null) {
              Value(qualifier = Fact.Known(element.value as? String, site(element)))
            } else {
              Value()
            }
          is IrClassReference -> Value(keyClass = runtimeKeyClass(element.classType))
          is IrVararg -> {
            element.elements.forEach(::evaluate)
            Value()
          }
          is IrSpreadElement -> evaluate(element.expression)
          is IrFunctionExpression -> Value(callable = true)
          is IrFunctionReference -> {
            // Creating a reference evaluates bound receivers, not the referenced body.
            evaluatedChildren(element).forEach(::evaluate)
            Value(callable = true)
          }
          is IrFunctionAccessExpression -> operation(element)
          is IrGetField -> field(element)
          is IrGetObjectValue -> {
            if (element.symbol.owner.fqNameWhenAvailable?.asString() != "kotlin.Unit") {
              unknown(
                element,
                "Object initialization is unsupported",
              )
            }
            Value(dispatch = DispatchReceiver.Unknown("Object initialization"))
          }
          is IrWhen, is IrLoop, is IrTry -> {
            if (!provenHarmless(element) || hasEarlyReturn(element)) {
              unknown(
                element,
                "Unsupported control flow with Mosaic capabilities",
              )
            }
            Value()
          }
          is IrSetField -> {
            element.receiver?.let(::evaluate)
            evaluate(element.value)
            if (unsupportedFieldValue(element.value.type)) {
              unknown(element, "Unsupported mutable or escaped field provenance")
            }
            Value()
          }
          is IrSetValue -> {
            evaluate(element.value)
            if (isCapabilityType(
                element.symbol.owner.type,
              )
            ) {
              unknown(element, "Unsupported mutable capability provenance")
            }
            Value()
          }
          else -> {
            if (!provenHarmless(
                element,
              )
            ) {
              unknown(element, "Unsupported capability-bearing IR ${element::class.simpleName}")
            }
            Value()
          }
        }
      return result.copy(callable = result.callable || element is IrExpression && isCallableType(element.type))
    }

    private fun parameterValue(expression: IrGetValue): Value {
      val parameter = expression.symbol.owner as? IrValueParameter ?: return Value()
      return when {
        parameter.kind == IrParameterKind.DispatchReceiver -> Value(dispatch = DispatchReceiver.Forwarded)
        parameter.kind == IrParameterKind.Regular && isCanvasType(parameter.type) ->
          Value(
            canvas =
              CanvasExpression.ParameterValue(
                ContractParameter(owner, parameter.name.asString(), ParameterKind.CANVAS),
              ),
          )
        parameter.type.classFqName?.asString() == "org.buildmosaic.core.Mosaic" -> {
          val reason = "Mosaic helper parameter transfer is unsupported for $owner"
          unknown(expression, reason)
          Value(mosaic = CanvasExpression.Unknown(reason, site(expression)))
        }
        isCallableType(parameter.type) -> Value(callable = true)
        else -> Value(dispatch = DispatchReceiver.Unknown("Dispatch receiver parameter or mutable value"))
      }
    }

    private fun field(expression: IrGetField): Value {
      evaluatedChildren(expression).forEach(::evaluate)
      val property = expression.symbol.owner.correspondingPropertySymbol?.owner
      if (property != null && isKeyType(expression.type)) {
        propertyKey(property, expression)?.let { return it }
      }
      if (property?.isDelegated == true) unknown(expression, "Unsupported delegated property initialization")
      val initializer = expression.symbol.owner.initializer?.expression
      if (property?.parent is IrFile && initializer != null) return evaluate(initializer)
      if (isCapabilityType(expression.type) || initializer == null) {
        unknown(expression, "Stored field initialization or receiver provenance is unavailable")
      }
      return Value()
    }

    /** Receiver and actuals are visited in compiler IR order, including its reordering temporaries. */
    private fun prepare(call: IrFunctionAccessExpression): Map<IrValueParameter, Value> {
      val actuals = linkedMapOf<IrValueParameter, Value>()
      call.symbol.owner.parameters.forEach { parameter ->
        call.arguments[parameter]?.let { argument ->
          // Kotlin creates these unbound references for delegation; their accessors do not run.
          val delegateReference =
            isTileDelegate(call) && parameter.name.asString() == "property" &&
              (argument is IrPropertyReference || argument is IrLocalDelegatedPropertyReference)
          actuals[parameter] =
            if (delegateReference) {
              evaluatedChildren(argument).forEach(::evaluate)
              Value()
            } else {
              evaluate(argument)
            }
        }
      }
      return actuals
    }

    private fun operation(call: IrFunctionAccessExpression): Value {
      val function = call.symbol.owner
      val target = if (call is IrCall) resolvedName(call) else function.fqNameWhenAvailable?.asString().orEmpty()
      val actuals = prepare(call)
      localTileDelegates[(function as? IrSimpleFunction)?.symbol]?.let { delegate ->
        return values[delegate] ?: Value()
      }
      val intrinsic =
        call is IrCall && isIntrinsic(target) || isTileFactory(call) || isTileDelegate(call) ||
          target == "org.buildmosaic.core.injection.CanvasKey.<init>"
      if (!intrinsic) effects += usedDefaultEffects(call, owner)
      val receiver =
        actuals.entries.firstOrNull { it.key.kind == IrParameterKind.ExtensionReceiver }
          ?.value ?: actuals.entries.firstOrNull { it.key.kind == IrParameterKind.DispatchReceiver }?.value ?: Value()

      fun argument(name: String) = actuals.entries.firstOrNull { it.key.name.asString() == name }?.value ?: Value()
      if (call is IrConstructorCall && target == "org.buildmosaic.core.injection.CanvasKey.<init>") {
        val qualifier = if (call.argument("qualifier") == null) null else argument("qualifier").qualifier ?: Fact.Unknown("Dynamic Canvas qualifier", site(call))
        return Value(key = resolvedKey(argument("type").keyClass, qualifier, site(call)))
      }
      if (intrinsic) {
        when {
          target == "org.buildmosaic.core.Mosaic.<get-canvas>" ->
            return Value(
              canvas = receiver.mosaic ?: CanvasExpression.Unknown("Unresolved Mosaic receiver transfer", site(call)),
            )
          isSource(target) && call is IrCall -> {
            val lookup =
              if (target.startsWith("org.buildmosaic.core.injection.")) {
                canvas(receiver, call)
              } else {
                receiver.mosaic ?: CanvasExpression.Unknown("Unresolved Mosaic receiver transfer", site(call))
              }
            effects += Effect.Lookup(valueId(call), lookup, key(call, actuals, owner), if (target.endsWith("sourceOrNull")) LookupKind.OPTIONAL else LookupKind.REQUIRED, site(call))
            return Value(callable = isCallableType(call.type))
          }
          target == "org.buildmosaic.core.injection.CanvasFactory.source" && call is IrCall -> {
            effects += Effect.Lookup(valueId(call), receiver.canvas ?: CanvasExpression.Unknown("CanvasFactory receiver transfer is unsupported", site(call)), key(call, actuals, owner), LookupKind.CONSTRUCTION, site(call))
            return Value(callable = isCallableType(call.type))
          }
          (target == "org.buildmosaic.core.Mosaic.compose" || target == "org.buildmosaic.core.Mosaic.composeAsync") && call is IrCall -> {
            effects += Effect.Compose(valueId(call), receiver.mosaic ?: CanvasExpression.Unknown("Unresolved Mosaic receiver transfer", site(call)), argument("tile").tile ?: TileReference.Unknown("Unsupported Tile value provenance", site(call)), if (target.endsWith("composeAsync")) DiscoveryKind.COMPOSE_ASYNC else DiscoveryKind.COMPOSE, multiTileExecution(call), site(call), receiver.mosaicIdentity)
            return Value(callable = isCallableType(call.type))
          }
          target == "org.buildmosaic.core.injection.withMosaic" -> {
            val mosaic = establishMosaic(call, receiver)
            val block = lambdaFunction(call.argument("block"))
            if (block?.body == null) {
              unknown(call, "Unsupported withMosaic block; a direct lambda is required")
              return Value()
            }
            val nested = Normalizer(file, owner, values.toMutableMap(), suppressionScope = suppressionScope)
            block.parameters.filter { it.kind == IrParameterKind.ExtensionReceiver }.forEach {
              nested.values[it.symbol] = mosaic
            }
            val result = nested.body(block.body)
            effects += nested.effects
            if (result.mosaic != null) unknown(call, "Unsupported escaped withMosaic receiver")
            // Escaped receivers and arbitrary helper transfer have no supported result provenance.
            return Value(callable = result.callable)
          }
          isTileFactory(call) -> return freshTile(call)
          isTileDelegate(call) -> return receiver
          else -> {
            check(call is IrCall)
            val parent =
              if (target.endsWith(
                  "withLayer",
                )
              ) {
                canvas(receiver, call)
              } else {
                argument("parent").canvas ?: CanvasExpression.Empty
              }
            return materialize(call, layer(call, parent))
          }
        }
      }
      val getter = (function as? IrSimpleFunction)?.correspondingPropertySymbol?.owner
      if (getter != null && isKeyType(call.type)) {
        propertyKey(getter, call)?.let { return it }
      }
      val eligibility = callableEligibility(call) { actuals[it]?.callable == true }
      val boundary = (eligibility as? CallableEligibility.Unsupported)?.reason
      if (boundary != null) {
        if (isCanvasType(call.type)) return materialize(call, CanvasExpression.Unknown(boundary, site(call)))
        unknown(call, boundary)
        return Value(callable = isCallableType(call.type))
      }
      if (eligibility == CallableEligibility.EffectFree) return Value(callable = isCallableType(call.type))
      val arguments =
        CallArguments(
          actuals.mapNotNull { (parameter, value) ->
            if (parameter.kind == IrParameterKind.Regular && isCanvasType(parameter.type)) {
              ContractParameter(symbolId(function), parameter.name.asString(), ParameterKind.CANVAS) to ArgumentExpression.Canvas(canvas(value, call))
            } else {
              null
            }
          }.toMap(),
        )
      val dispatch =
        actuals.entries.firstOrNull {
          it.key.kind == IrParameterKind.DispatchReceiver
        }?.value?.dispatch ?: DispatchReceiver.None
      val virtual = function is IrSimpleFunction && function.modality != Modality.FINAL
      if (isCanvasType(
          call.type,
        )
      ) {
        return materialize(
          call,
          CanvasExpression.RuntimeCall(
            symbolId(function),
            arguments,
            site(call),
            receiver = dispatch,
            virtualDispatch = virtual,
          ),
        )
      }
      effects += Effect.Call(valueId(call), symbolId(function), arguments, site(call), dispatch, virtual)
      if (call is IrConstructorCall) {
        val clazz = function.parent as? IrClass
        return Value(
          dispatch =
            if (clazz?.modality == Modality.FINAL) {
              DispatchReceiver.Concrete(
                clazz.fqNameWhenAvailable!!.asString(),
              )
            } else {
              DispatchReceiver.Unknown("Receiver is not final")
            },
        )
      }
      val tile =
        if (getter != null && isTileType(call.type)) {
          when (tilePropertyEligibility(getter)) {
            TilePropertyEligibility.LOCAL_STABLE -> TileReference.Stable(propertyId(getter))
            TilePropertyEligibility.REQUIRES_EXPORT -> TileReference.ExportedProperty(propertyId(getter), site(call))
            TilePropertyEligibility.UNSUPPORTED ->
              TileReference.Unknown("Unsupported member-dependent or computed Tile property", site(call))
          }
        } else {
          null
        }
      return Value(
        tile = tile,
        key = if (isKeyType(call.type)) Fact.Unknown("Unsupported computed CanvasKey value", site(call)) else null,
        callable = isCallableType(call.type),
      )
    }

    private fun establishMosaic(
      call: IrFunctionAccessExpression,
      receiver: Value,
    ): Value {
      val snapshot = materialize(call, canvas(receiver, call)).canvas!!
      val id = valueId(call)
      effects += Effect.EstablishMosaic(id, snapshot, site(call))
      return Value(mosaic = snapshot, mosaicIdentity = MosaicProvenance.Established(id))
    }

    private fun freshTile(call: IrFunctionAccessExpression): Value {
      if (!collectFreshTemplates) return Value()
      val templateId = "$owner:fresh:${identity(call)}"
      val lambda = lambdaFunction(call.argument("block") ?: call.argument("fetch"))
      val template = Normalizer(file, templateId, values.toMutableMap(), suppressionScope = suppressionScope)
      val capture = lambda?.let { unsupportedTileCapture(it, values) }
      if (capture != null) {
        template.unknown(call, "Unsupported deferred Tile capture: $capture")
      } else if (lambda?.body != null) {
        lambda.parameters.filter { it.kind == IrParameterKind.ExtensionReceiver }.forEach {
          template.bindCurrentReceiver(it, mosaic = true)
        }
        template.body(lambda.body)
      } else {
        template.unknown(call, "Tile block is unavailable")
      }
      freshTemplates[templateId] =
        TileContract(
          templateId,
          template.effects,
          site(call),
          suppressions = suppressionScope,
          multi = call.type.classFqName?.asString() == "org.buildmosaic.core.MultiTile",
        )
      return Value(tile = TileReference.Fresh(templateId, valueId(call), owner))
    }

    private fun layer(
      call: IrCall,
      parent: CanvasExpression,
    ): CanvasExpression {
      val bindings = mutableListOf<Binding>()
      val unknown = mutableListOf<UnknownRegistration>()
      val build = lambdaFunction(call.argument("build"))
      val body = build?.body as? IrBlockBody
      val builder = Normalizer(file, owner, values.toMutableMap(), suppressionScope = suppressionScope)
      build?.parameters?.filter { it.kind == IrParameterKind.ExtensionReceiver }?.forEach {
        builder.values[it.symbol] = Value(builder = true)
      }
      if (body == null) unknown += UnknownRegistration("Canvas builder body unavailable", site(call))
      body?.statements?.forEach { statement ->
        if (statement is IrCall && resolvedName(statement) in
          setOf(
            "org.buildmosaic.core.injection.CanvasBuilder.provide",
            "org.buildmosaic.core.injection.CanvasBuilder.instance",
          )
        ) {
          val prepared = builder.prepare(statement)
          if (prepared.entries.none { it.key.kind == IrParameterKind.DispatchReceiver && it.value.builder }) {
            builder.unknown(statement, "Unsupported Canvas registration receiver")
            unknown += UnknownRegistration("Unsupported registration receiver", site(statement))
            return@forEach
          }
          if (resolvedName(statement).endsWith(".instance")) {
            // prepare already evaluated key/qualifier and value in source order.
            // Normalize by parameter name, just like provide; borrowed values have no constructor effects.
            bindings += Binding(key(statement, prepared, owner), site = site(statement))
            return@forEach
          }
          val ctor = statement.argument("ctor")
          val provider = Normalizer(file, owner, builder.values.toMutableMap())
          val function = lambdaFunction(ctor)
          function?.parameters?.filter { it.kind == IrParameterKind.ExtensionReceiver }?.forEach {
            provider.bindCurrentReceiver(it, mosaic = false)
          }
          val ctorBody = function?.body
          if (ctorBody == null) {
            provider.unknown(
              statement,
              "Binding constructor body unavailable",
            )
          } else {
            provider.evaluate(ctorBody)
          }
          bindings += Binding(key(statement, prepared, owner), provider.effects, site(statement))
        } else if (statement is IrVariable) {
          builder.evaluate(statement)
        } else if (!provenHarmless(statement)) {
          builder.unknown(statement, "Unsupported Canvas registration effects")
          unknown += UnknownRegistration("Unsupported Canvas registration", site(statement))
        }
      }
      // Registration expressions execute before eager provider construction.
      effects += builder.effects
      return CanvasExpression.Layer(valueId(call), parent, bindings, unknown, site(call))
    }
  }

  private enum class TilePropertyEligibility { LOCAL_STABLE, REQUIRES_EXPORT, UNSUPPORTED }

  /** The same property proof gates declaration export and reference resolution. */
  private fun tilePropertyEligibility(property: IrProperty): TilePropertyEligibility {
    if (property.parent !is IrPackageFragment || property.isVar) return TilePropertyEligibility.UNSUPPORTED
    // Binary IR cannot prove what the getter returns. Defer to the selected producer's export.
    if (property.parent !is IrFile) return TilePropertyEligibility.REQUIRES_EXPORT
    return if (tileInitializer(property) != null) {
      TilePropertyEligibility.LOCAL_STABLE
    } else {
      TilePropertyEligibility.UNSUPPORTED
    }
  }

  /** Unwrap only Mosaic's binding operator and its compiler-generated getter. */
  private fun tileInitializer(property: IrProperty): IrFunctionAccessExpression? {
    val initializer = property.backingField?.initializer?.expression as? IrFunctionAccessExpression ?: return null
    if (!property.isDelegated) {
      return initializer.takeIf {
        property.getter?.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR && isTileFactory(it)
      }
    }
    if (!isTileDelegate(initializer, "provideDelegate")) return null
    val read = property.getter?.let(::delegatedGetterCall) ?: return null
    val field = read.dispatchReceiverValue() as? IrGetField ?: return null
    if (!isTileDelegate(read, "getValue") || field.symbol != property.backingField?.symbol) return null
    return (initializer.dispatchReceiverValue() as? IrFunctionAccessExpression)?.takeIf(::isTileFactory)
  }

  private fun localTileDelegate(property: IrLocalDelegatedProperty): IrVariable? {
    if (property.isVar) return null
    val delegate = property.delegate ?: return null
    val binding = delegate.initializer as? IrCall ?: return null
    if (!isTileDelegate(binding, "provideDelegate")) return null
    val read = delegatedGetterCall(property.getter) ?: return null
    if (!isTileDelegate(read, "getValue")) return null
    val receiver = read.dispatchReceiverValue() as? IrGetValue ?: return null
    return delegate.takeIf { receiver.symbol == it.symbol }
  }

  private fun delegatedGetterCall(getter: IrSimpleFunction): IrCall? {
    if (getter.origin != IrDeclarationOrigin.DELEGATED_PROPERTY_ACCESSOR) return null
    val statement = (getter.body as? IrBlockBody)?.statements?.singleOrNull() as? IrReturn ?: return null
    return statement.value as? IrCall
  }

  private fun IrFunctionAccessExpression.dispatchReceiverValue(): IrExpression? =
    symbol.owner.parameters.singleOrNull { it.kind == IrParameterKind.DispatchReceiver }?.let { arguments[it] }

  private fun isTileDelegate(
    call: IrFunctionAccessExpression,
    operator: String? = null,
  ): Boolean {
    if (call !is IrCall) return false
    val member = call.symbol.owner.name.asString()
    if (operator != null && member != operator) return false
    if (member != "provideDelegate" && member != "getValue") return false
    val generatedReference =
      when (val property = call.argument("property")) {
        is IrPropertyReference -> property.origin == IrStatementOrigin.PROPERTY_REFERENCE_FOR_DELEGATE
        is IrLocalDelegatedPropertyReference -> property.origin == IrStatementOrigin.PROPERTY_REFERENCE_FOR_DELEGATE
        else -> false
      }
    if (!generatedReference) return false
    val name = resolvedName(call)
    return name == "org.buildmosaic.core.Tile.$member" || name == "org.buildmosaic.core.MultiTile.$member"
  }

  private fun isIntrinsic(target: String): Boolean =
    isSource(target) || target in
      setOf(
        "org.buildmosaic.core.Mosaic.<get-canvas>",
        "org.buildmosaic.core.injection.CanvasKey.<init>",
        "org.buildmosaic.core.injection.CanvasFactory.source", "org.buildmosaic.core.Mosaic.compose",
        "org.buildmosaic.core.Mosaic.composeAsync",
        "org.buildmosaic.core.injection.withMosaic",
        "org.buildmosaic.core.injection.canvas", "org.buildmosaic.core.injection.Canvas.withLayer",
        "org.buildmosaic.core.singleTile", "org.buildmosaic.core.multiTile", "org.buildmosaic.core.perKeyTile",
        "org.buildmosaic.core.chunkedMultiTile",
        "org.buildmosaic.core.Tile.<init>", "org.buildmosaic.core.MultiTile.<init>",
      )

  private sealed interface CallableEligibility {
    data object EffectFree : CallableEligibility

    data object Contract : CallableEligibility

    data class Unsupported(val reason: String) : CallableEligibility
  }

  /** Shared by execution and structural omission; a body cannot override an unsupported boundary. */
  private fun callableEligibility(
    call: IrFunctionAccessExpression,
    callableActual: (IrValueParameter) -> Boolean = { call.arguments[it]?.let(::callableValue) == true },
  ): CallableEligibility {
    val function = call.symbol.owner
    if (isTileDelegate(call)) return CallableEligibility.EffectFree
    val intrinsic = intrinsicEligibility(call)
    val boundary =
      when {
        intrinsic is CallableEligibility.Unsupported -> intrinsic.reason
        function is IrSimpleFunction && function.isInline && intrinsic != CallableEligibility.EffectFree ->
          "External inline capability helper ${symbolId(function)} has no pre-inline body"
        function.parameters.any {
          it.kind == IrParameterKind.ExtensionReceiver && it.type.classFqName?.asString() == "org.buildmosaic.core.Mosaic"
        } -> "Mosaic extension receiver transfer is unsupported for ${symbolId(function)}"
        (function as? IrSimpleFunction)?.correspondingPropertySymbol?.owner?.let {
          it.isDelegated && tilePropertyEligibility(it) == TilePropertyEligibility.UNSUPPORTED
        } == true -> "Unsupported delegated property accessor"
        function.parameters.any {
          it.kind != IrParameterKind.Regular && callableActual(it)
        } -> "Unsupported callable invocation"
        function.parameters.any(callableActual) -> "Unsupported capability-bearing callback escape"
        else -> null
      }
    return boundary?.let(CallableEligibility::Unsupported) ?: intrinsic
  }

  /** Read immutable compiler bindings as facts, without replaying their initialization effects. */
  private fun callableValue(expression: IrExpression): Boolean =
    when {
      expression is IrFunctionReference || expression is IrFunctionExpression || isCallableType(expression.type) -> true
      expression is IrTypeOperatorCall -> callableValue(expression.argument)
      expression is IrGetValue ->
        (expression.symbol.owner as? IrVariable)?.takeUnless { it.isVar }?.initializer?.let(::callableValue) == true
      else -> false
    }

  private enum class ImplicitCallback(val description: String) {
    EQUALITY("equals"),
    HASHING("hashCode or equals"),
    STRING_CONVERSION("toString"),
  }

  /** Exact built-ins plus type proofs for operations with implicit callbacks. */
  private fun intrinsicEligibility(call: IrFunctionAccessExpression): CallableEligibility {
    val target = call.symbol.owner.fqNameWhenAvailable?.asString().orEmpty()
    val actuals = call.arguments.filterNotNull()
    val callback =
      when (target) {
        "kotlin.internal.ir.EQEQ" -> ImplicitCallback.EQUALITY
        "kotlin.collections.setOf", "kotlin.collections.mutableSetOf",
        "kotlin.collections.mapOf", "kotlin.collections.mutableMapOf",
        -> ImplicitCallback.HASHING
        "kotlin.error" -> ImplicitCallback.STRING_CONVERSION
        else -> null
      }
    if (callback != null) {
      val safe =
        if (callback == ImplicitCallback.HASHING) {
          // Hashing depends on elements (sets) or keys (maps), never map values.
          call.typeArguments.firstOrNull()?.let(::scalarType) == true ||
            actuals.all { it is IrVararg && it.elements.isEmpty() }
        } else {
          actuals.all { scalarType(it.type) || it is IrConst && it.value == null }
        }
      return if (safe) {
        CallableEligibility.EffectFree
      } else {
        CallableEligibility.Unsupported(
          "Implicit ${callback.description} callback is unsupported for $target",
        )
      }
    }
    return if (target in effectFreeOperations || target in primitiveOperations) CallableEligibility.EffectFree else CallableEligibility.Contract
  }

  private fun scalarType(type: IrType): Boolean = type.classFqName?.asString() in scalarClasses

  private val scalarClasses =
    setOf("Int", "Long", "Float", "Double", "Short", "Byte", "Char", "Boolean", "String").map { "kotlin.$it" }.toSet()

  private val effectFreeOperations =
    setOf(
      "kotlin.Any.<init>", "kotlin.String.toString",
      "kotlin.collections.emptyList", "kotlin.collections.emptySet", "kotlin.collections.emptyMap",
      "kotlin.collections.listOf", "kotlin.collections.mutableListOf", "kotlin.collections.arrayListOf",
      "kotlin.arrayOf", "kotlin.intArrayOf", "kotlin.emptyArray", "kotlin.internal.ir.less",
      "kotlin.internal.ir.greater", "kotlin.internal.ir.lessOrEqual", "kotlin.internal.ir.greaterOrEqual",
      "java.lang.System.nanoTime",
      "org.buildmosaic.core.Tile.<get-name>", "org.buildmosaic.core.MultiTile.<get-name>",
    )

  private val primitiveOperations =
    setOf("Int", "Long", "Float", "Double", "Short", "Byte", "Char", "Boolean").flatMap { type ->
      setOf("plus", "minus", "times", "div", "rem", "inc", "dec", "compareTo", "equals", "not", "toString").map {
        "kotlin.$type.$it"
      }
    }.toSet()

  /** Creation evaluates captures, not deferred bodies. Field access evaluates its receiver. */
  private fun evaluatedChildren(element: IrElement): List<IrElement> =
    when (element) {
      is IrFunctionExpression -> emptyList()
      is IrFunctionReference -> element.arguments.filterNotNull()
      is IrGetField -> listOfNotNull(element.receiver)
      else ->
        buildList {
          element.acceptChildrenVoid(
            object : IrVisitorVoid() {
              override fun visitElement(element: IrElement) {
                add(element)
              }
            },
          )
        }
    }

  /** A return inside an omitted control-flow region can bypass later Mosaic effects. */
  private fun hasEarlyReturn(element: IrElement): Boolean =
    element is IrReturn || evaluatedChildren(element).any(::hasEarlyReturn)

  private fun unsupportedFieldValue(type: IrType): Boolean = isCapabilityType(type) || isCallableType(type)

  /** A bounded structural proof used only to omit demonstrably irrelevant computation. */
  private fun provenHarmless(
    element: IrElement,
    active: Set<IrFunction> = emptySet(),
  ): Boolean {
    fun children(): Boolean = evaluatedChildren(element).all { provenHarmless(it, active) }
    return when (element) {
      is IrConst, is IrGetValue -> true
      is IrFunctionExpression, is IrFunctionReference -> children() // evaluated captures only, never the body
      is IrFunctionAccessExpression -> {
        val function = element.symbol.owner
        val name = function.fqNameWhenAvailable?.asString().orEmpty()
        val eligibility = callableEligibility(element)
        val actualsHarmless = children()
        val defaultsHarmless =
          function.parameters.filter {
            it.kind == IrParameterKind.Regular && element.arguments[it] == null && it.varargElementType == null
          }.all { it.defaultValue?.let { default -> provenHarmless(default, active) } == true }
        if (!actualsHarmless || !defaultsHarmless || eligibility is CallableEligibility.Unsupported) {
          false
        } else if (eligibility == CallableEligibility.EffectFree) {
          true
        } else if (function is IrSimpleFunction && function.modality == Modality.FINAL && !function.isInline) {
          function.body?.takeIf { function !in active && !isIntrinsic(name) }?.let {
            provenHarmless(it, active + function)
          } == true
        } else {
          false
        }
      }
      is IrGetField ->
        children() && !isCapabilityType(element.type) &&
          element.symbol.owner.correspondingPropertySymbol?.owner?.isDelegated != true && element.symbol.owner.initializer?.let {
            provenHarmless(it, active)
          } == true
      is IrGetObjectValue -> element.symbol.owner.fqNameWhenAvailable?.asString() == "kotlin.Unit"
      is IrSetField -> !unsupportedFieldValue(element.value.type) && children()
      is IrSetValue -> !isCapabilityType(element.symbol.owner.type) && children()
      is IrVariable -> (!element.isVar || !isCapabilityType(element.type)) && children()
      is IrInstanceInitializerCall -> true // constructor adapter handles stored initializers explicitly
      is IrBlockBody, is IrBlock, is IrExpressionBody, is IrReturn,
      is IrTypeOperatorCall, is IrWhen, is IrBranch, is IrLoop, is IrVararg, is IrSpreadElement, is IrThrow,
      -> children()
      else -> false
    }
  }

  private fun usedDefaultEffects(
    call: IrFunctionAccessExpression,
    owner: String,
  ): List<Effect> =
    call.symbol.owner.parameters.filter {
      it.kind == IrParameterKind.Regular && call.arguments[it] == null && it.varargElementType == null
    }.mapNotNull { parameter ->
      val default = parameter.defaultValue
      if (default != null && provenHarmless(default)) return@mapNotNull null
      val unavailable = default == null || default.expression is IrErrorExpression
      Effect.Unknown(
        "$owner:default:${identity(call)}:${parameter.indexInParameters}",
        if (unavailable) {
          "Default expression is unavailable for ${symbolId(call.symbol.owner)}.${parameter.name}"
        } else {
          "Capability-bearing default expression is unsupported for ${symbolId(call.symbol.owner)}.${parameter.name}"
        },
        location(call, owner),
      )
    }

  private fun isCallableType(type: IrType): Boolean =
    type.classFqName?.asString()?.let {
      it.startsWith("kotlin.Function") || it.startsWith("kotlin.coroutines.SuspendFunction") ||
        it.startsWith("kotlin.reflect.KFunction") || it.startsWith("kotlin.reflect.KSuspendFunction")
    } == true

  private fun isTileType(type: IrType): Boolean =
    type.classFqName?.asString() in setOf("org.buildmosaic.core.Tile", "org.buildmosaic.core.MultiTile")

  private fun isKeyType(type: IrType): Boolean =
    type.classFqName?.asString() == "org.buildmosaic.core.injection.CanvasKey"

  private fun key(
    call: IrCall,
    actuals: Map<IrValueParameter, Value>,
    owner: String,
  ): Fact<CanvasKeyIdentity> {
    val site = location(call, owner)

    fun actual(name: String): Value? = actuals.entries.firstOrNull { it.key.name.asString() == name }?.value
    actual("key")?.let { return it.key ?: Fact.Unknown("Dynamic CanvasKey value", site) }
    val classId =
      if (call.symbol.owner.parameters.any { it.name.asString() == "type" }) {
        actual("type")?.keyClass
      } else {
        call.typeArguments.firstOrNull()?.let(::runtimeKeyClass)
      }
    val qualifier = if (call.argument("qualifier") == null) null else actual("qualifier")?.qualifier ?: Fact.Unknown("Dynamic Canvas qualifier", site)
    if (qualifier is Fact.Known && qualifier.value != null) {
      limitations += "Qualifier literal at ${site.path}:${site.line} in $owner has no reliable external const origin in pre-inline IR"
    }
    return resolvedKey(classId, qualifier, site)
  }

  private fun resolvedKey(
    classId: String?,
    qualifier: Fact<String?>?,
    site: SourceLocation,
  ): Fact<CanvasKeyIdentity> {
    if (classId == null) return Fact.Unknown("Dynamic KClass or unavailable runtime Canvas key class", site)
    val literal = qualifier ?: Fact.Known(null, site)
    return if (literal is Fact.Known) {
      Fact.Known(CanvasKeyIdentity(classId, literal.value), site)
    } else {
      Fact.Unknown("Dynamic Canvas qualifier", site)
    }
  }

  private fun propertyKey(
    property: IrProperty,
    element: IrElement,
  ): Value? {
    val site = location(element, propertyId(property))
    val stable =
      property.parent is IrPackageFragment && !property.isVar && !property.isDelegated &&
        (property.parent !is IrFile || property.getter?.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR)
    return if (stable && (property.parent !is IrFile || stableKeyProperty(property) != null)) {
      Value(key = Fact.ExportedKey(propertyId(property), site))
    } else {
      null
    }
  }

  private fun stableKeyProperty(property: IrProperty): CanvasKeyIdentity? {
    if (property.parent !is IrFile || property.isVar || property.isDelegated) return null
    if (property.getter?.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) return null
    val initializer = property.backingField?.initializer?.expression as? IrConstructorCall ?: return null
    if (initializer.symbol.owner.fqNameWhenAvailable?.asString() != "org.buildmosaic.core.injection.CanvasKey.<init>") return null
    val type = initializer.argument("type") as? IrClassReference ?: return null
    val classId = runtimeKeyClass(type.classType) ?: return null
    val qualifier = initializer.argument("qualifier")
    if (qualifier != null && qualifier !is IrConst) return null
    val literal = qualifier?.value
    if (literal != null && literal !is String) return null
    return CanvasKeyIdentity(classId, literal)
  }

  private fun multiTileExecution(call: IrCall): MultiTileExecution {
    val tileParameter = call.symbol.owner.parameters.firstOrNull { it.name.asString() == "tile" }
    if (tileParameter?.type?.classFqName?.asString() != "org.buildmosaic.core.MultiTile") return MultiTileExecution.NOT_APPLICABLE
    if (call.argument("key") != null) return MultiTileExecution.KNOWN_NON_EMPTY
    val keys = call.argument("keys") ?: return MultiTileExecution.UNKNOWN
    if (keys is IrCall) {
      val name = resolvedName(keys)
      if (name in setOf("kotlin.collections.emptyList", "kotlin.collections.emptySet")) return MultiTileExecution.KNOWN_EMPTY
      if (name in setOf("kotlin.collections.listOf", "kotlin.collections.setOf", "kotlin.collections.mutableListOf", "kotlin.collections.mutableSetOf", "kotlin.collections.arrayListOf")) {
        val items = keys.arguments.filterIsInstance<IrVararg>().flatMap { it.elements }
        val direct = keys.arguments.filterNotNull().filterNot { it is IrVararg }
        if (items.isEmpty() && direct.isEmpty()) return MultiTileExecution.KNOWN_EMPTY
        if (direct.isNotEmpty()) return MultiTileExecution.KNOWN_NON_EMPTY
        if (items.none { it is IrSpreadElement }) return MultiTileExecution.KNOWN_NON_EMPTY
      }
    }
    return MultiTileExecution.UNKNOWN
  }

  private fun lambdaFunction(expression: IrExpression?): IrFunction? =
    when (expression) {
      is IrFunctionExpression -> expression.function
      is IrBlock -> expression.statements.filterIsInstance<IrFunctionExpression>().lastOrNull()?.function
      else -> null
    }

  /** Relevant outer values need an explicit deferred snapshot; scalar facts can be copied safely. */
  private fun unsupportedTileCapture(
    function: IrFunction,
    outerValues: Map<org.jetbrains.kotlin.ir.symbols.IrValueSymbol, Value>,
  ): String? {
    val local: MutableSet<org.jetbrains.kotlin.ir.symbols.IrValueSymbol> =
      function.parameters.map {
        it.symbol
      }.toMutableSet()
    function.body?.acceptChildrenVoid(
      object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
          if (element is IrVariable) local += element.symbol
          element.acceptChildrenVoid(this)
        }
      },
    )
    var reason: String? = null
    function.body?.acceptChildrenVoid(
      object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
          if (element is IrGetValue && element.symbol !in local) {
            val value = outerValues[element.symbol]
            reason = unsupportedCaptureReason(element.type, value) ?: reason
          }
          if (element is IrCall) {
            localTileDelegates[element.symbol]?.takeIf { it !in local }?.let { delegate ->
              reason = unsupportedCaptureReason(element.type, outerValues[delegate]) ?: reason
            }
          }
          element.acceptChildrenVoid(this)
        }
      },
    )
    return reason
  }

  private fun unsupportedCaptureReason(
    type: IrType,
    value: Value?,
  ): String? {
    if (isKeyType(type)) {
      return when (value?.key) {
        is Fact.Known, is Fact.ExportedKey -> null
        else -> "CanvasKey value provenance is unavailable"
      }
    }
    val capabilityType = isCapabilityType(type) || isCallableType(type)
    val capabilityValue = value?.canvas != null || value?.mosaic != null || value?.tile != null || value?.callable == true
    return if (capabilityType || capabilityValue) type.classFqName?.asString() ?: "capability value" else null
  }
}
