package org.buildmosaic.analysis.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class WireEnvelope(
  val contractVersion: Int,
  val producer: WireProducer,
  val moduleId: String,
  val sourceSet: String,
  val complete: Boolean,
  val payloadHash: String,
  val payload: WirePayload,
)

@Serializable internal data class WireProducer(val analysisVersion: String, val compilerVersion: String)

@Serializable internal data class WirePayload(
  val module: WireModule,
  val limitations: List<String>,
  val binaryLocators: List<WireLocator>,
  val coreAnalysisRevisions: List<Int>,
)

@Serializable internal data class WireLocator(val id: String, val locator: String)

@Serializable internal data class WireSite(val owner: String, val path: String, val line: Int, val column: Int)

@Serializable internal data class WireKey(val classId: String, val qualifier: String?)

@Serializable internal data class WireParameter(val owner: String, val name: String, val kind: String)

@Serializable internal data class WireActual(val parameter: WireParameter, val value: WireArgument)

@Serializable internal data class WireArguments(val values: List<WireActual>)

@Serializable internal sealed interface WireFact {
  @Serializable
  @SerialName("known")
  data class Known(
    val value: WireKey,
    val provenance: WireSite?,
    val evidence: String,
    val capturedOrigin: WireOrigin?,
  ) : WireFact

  @Serializable
  @SerialName("unknown")
  data class Unknown(val reason: String, val site: WireSite) : WireFact

  @Serializable
  @SerialName("exportedKey")
  data class ExportedKey(val declaration: String, val site: WireSite) : WireFact
}

@Serializable internal sealed interface WireOrigin {
  @Serializable
  @SerialName("inline")
  data class Inline(
    val declaration: String,
    val artifact: String,
    val contractHash: String,
  ) : WireOrigin

  @Serializable
  @SerialName("constant")
  data class Constant(val declaration: String, val artifact: String, val literal: String) : WireOrigin
}

@Serializable internal sealed interface WireBoolean {
  @Serializable
  @SerialName("constant")
  data class Constant(val value: Boolean) : WireBoolean

  @Serializable
  @SerialName("parameter")
  data class Parameter(val parameter: WireParameter) : WireBoolean

  @Serializable
  @SerialName("opaque")
  data class Opaque(val reason: String, val site: WireSite) : WireBoolean
}

@Serializable internal sealed interface WireGuard {
  @Serializable
  @SerialName("constant")
  data class Constant(val value: Boolean) : WireGuard

  @Serializable
  @SerialName("parameter")
  data class Parameter(val parameter: WireParameter, val expected: Boolean) : WireGuard

  @Serializable
  @SerialName("opaque")
  data class Opaque(val reason: String, val site: WireSite) : WireGuard
}

@Serializable internal sealed interface WireArgument {
  @Serializable
  @SerialName("canvas")
  data class Canvas(val expression: WireCanvas) : WireArgument

  @Serializable
  @SerialName("boolean")
  data class BooleanValue(val expression: WireBoolean) : WireArgument
}

@Serializable internal sealed interface WireReceiver {
  @Serializable
  @SerialName("none")
  data object None : WireReceiver

  @Serializable
  @SerialName("forwarded")
  data object Forwarded : WireReceiver

  @Serializable
  @SerialName("concrete")
  data class Concrete(val type: String) : WireReceiver

  @Serializable
  @SerialName("unknown")
  data class Unknown(val reason: String) : WireReceiver
}

@Serializable internal sealed interface WireCanvas {
  @Serializable
  @SerialName("empty")
  data object Empty : WireCanvas

  @Serializable
  @SerialName("current")
  data object Current : WireCanvas

  @Serializable
  @SerialName("parameter")
  data class Parameter(val parameter: WireParameter) : WireCanvas

  @Serializable
  @SerialName("layer")
  data class Layer(
    val id: String,
    val parent: WireCanvas,
    val bindings: List<WireBinding>,
    val unknownRegistrations: List<WireRegistration>,
    val site: WireSite,
  ) : WireCanvas

  @Serializable
  @SerialName("choice")
  data class Choice(val guard: WireGuard, val whenTrue: WireCanvas, val whenFalse: WireCanvas) : WireCanvas

  @Serializable
  @SerialName("runtimeCall")
  data class RuntimeCall(
    val target: String,
    val arguments: WireArguments,
    val site: WireSite,
    val callerEffects: List<WireEffect>,
    val receiver: WireReceiver,
    val virtualDispatch: Boolean,
  ) : WireCanvas

  @Serializable
  @SerialName("valueReference")
  data class ValueReference(val id: String, val site: WireSite) : WireCanvas

  @Serializable
  @SerialName("withEffects")
  data class WithEffects(val effects: List<WireEffect>, val result: WireCanvas) : WireCanvas

  @Serializable
  @SerialName("captured")
  data class Captured(
    val owner: String,
    val origin: WireOrigin,
    val expression: WireCanvas,
    val faithfullyCaptured: Boolean,
    val site: WireSite,
  ) : WireCanvas

  @Serializable
  @SerialName("assumption")
  data class Assumption(val id: String, val site: WireSite) : WireCanvas

  @Serializable
  @SerialName("alias")
  data class Alias(val id: String, val expression: WireCanvas) : WireCanvas

  @Serializable
  @SerialName("unknown")
  data class Unknown(val reason: String, val site: WireSite) : WireCanvas
}

@Serializable internal data class WireBinding(
  val key: WireFact,
  val constructorEffects: List<WireEffect>,
  val site: WireSite,
)

@Serializable internal data class WireRegistration(val reason: String, val site: WireSite)

@Serializable internal sealed interface WireTile {
  @Serializable
  @SerialName("stable")
  data class Stable(val contractId: String, val receiverId: String?) : WireTile

  @Serializable
  @SerialName("exportedProperty")
  data class ExportedProperty(val contractId: String, val site: WireSite) : WireTile

  @Serializable
  @SerialName("alias")
  data class Alias(val reference: WireTile) : WireTile

  @Serializable
  @SerialName("fresh")
  data class Fresh(val templateId: String, val allocationId: String, val invocationId: String) : WireTile

  @Serializable
  @SerialName("unknown")
  data class Unknown(val reason: String, val site: WireSite) : WireTile
}

@Serializable internal sealed interface WireEffect {
  @Serializable
  @SerialName("lookup")
  data class Lookup(
    val id: String,
    val canvas: WireCanvas,
    val key: WireFact,
    val lookupKind: String,
    val site: WireSite,
  ) : WireEffect

  @Serializable
  @SerialName("compose")
  data class Compose(
    val id: String,
    val canvas: WireCanvas,
    val tile: WireTile,
    val discovery: String,
    val execution: String,
    val site: WireSite,
    val mosaic: WireMosaic,
  ) : WireEffect

  @Serializable
  @SerialName("establishMosaic")
  data class EstablishMosaic(val id: String, val canvas: WireCanvas, val site: WireSite) : WireEffect

  @Serializable
  @SerialName("constructCanvas")
  data class ConstructCanvas(val id: String, val canvas: WireCanvas, val site: WireSite) : WireEffect

  @Serializable
  @SerialName("call")
  data class Call(
    val id: String,
    val target: String,
    val arguments: WireArguments,
    val site: WireSite,
    val receiver: WireReceiver,
    val virtualDispatch: Boolean,
  ) : WireEffect

  @Serializable
  @SerialName("branch")
  data class Branch(
    val id: String,
    val guard: WireGuard,
    val whenTrue: List<WireEffect>,
    val whenFalse: List<WireEffect>,
    val site: WireSite,
  ) : WireEffect

  @Serializable
  @SerialName("captured")
  data class Captured(
    val id: String,
    val owner: String,
    val origin: WireOrigin,
    val effects: List<WireEffect>,
    val faithfullyCaptured: Boolean,
    val site: WireSite,
  ) : WireEffect

  @Serializable
  @SerialName("unknown")
  data class Unknown(val id: String, val reason: String, val site: WireSite) : WireEffect
}

@Serializable internal data class WireCanvasContract(
  val id: String,
  val parameters: List<WireParameter>,
  val result: WireCanvas,
  val site: WireSite,
  val reusable: Boolean,
)

@Serializable internal data class WireTileContract(
  val id: String,
  val effects: List<WireEffect>,
  val site: WireSite,
  val reusable: Boolean,
  val multi: Boolean,
  val suppressions: List<WireSuppression>,
)

@Serializable internal data class WireCallableContract(
  val id: String,
  val parameters: List<WireParameter>,
  val effects: List<WireEffect>,
  val site: WireSite,
  val reusable: Boolean,
)

@Serializable internal data class WireOverrideSlot(
  val base: WireParameter,
  val implementation: WireParameter,
  val position: Int,
)

@Serializable internal data class WireOverride(
  val receiverType: String,
  val baseId: String,
  val implementationId: String,
  val slots: List<WireOverrideSlot>,
)

@Serializable internal data class WireModule(
  val id: String,
  val canvases: List<WireCanvasContract>,
  val tiles: List<WireTileContract>,
  val callables: List<WireCallableContract>,
  val overrides: List<WireOverride>,
  val keys: List<WireKeyContract>,
)

@Serializable internal data class WireKeyContract(val id: String, val key: WireKey, val site: WireSite)

@Serializable internal data class WireSuppression(val ruleId: String, val site: WireSite)

@Serializable internal sealed interface WireMosaic {
  @Serializable
  @SerialName("current")
  data object Current : WireMosaic

  @Serializable
  @SerialName("established")
  data class Established(val id: String) : WireMosaic

  @Serializable
  @SerialName("unknown")
  data object Unknown : WireMosaic
}
