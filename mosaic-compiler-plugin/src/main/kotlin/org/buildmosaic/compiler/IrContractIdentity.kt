@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
@file:Suppress("MaxLineLength", "TooManyFunctions", "ktlint:standard:max-line-length")

package org.buildmosaic.compiler

import org.buildmosaic.analysis.ContractParameter
import org.buildmosaic.analysis.ParameterKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import java.io.File

/** Stable IDs, JVM locators, runtime key identity, and source positions. */
internal fun normalizeKeyClass(classId: String): String =
  when (classId) {
    "kotlin.collections.MutableList" -> "kotlin.collections.List"
    "kotlin.collections.MutableSet" -> "kotlin.collections.Set"
    "kotlin.collections.MutableMap" -> "kotlin.collections.Map"
    else -> classId
  }

internal fun runtimeKeyClass(type: IrType): String? {
  val classId = type.classFqName?.asString() ?: return null
  if (classId != "kotlin.Array") return normalizeKeyClass(classId)
  val element = (type as? IrSimpleType)?.arguments?.singleOrNull() as? IrTypeProjection ?: return null
  return arrayComponentDescriptor(element.type)?.let { "[$it" }
}

internal fun arrayComponentDescriptor(type: IrType): String? {
  val classId = type.classFqName?.asString() ?: return null
  if (classId == "kotlin.Array") return runtimeKeyClass(type)
  val owner =
    when (classId) {
      "kotlin.Boolean" -> "java/lang/Boolean"
      "kotlin.Byte" -> "java/lang/Byte"
      "kotlin.Char" -> "java/lang/Character"
      "kotlin.Short" -> "java/lang/Short"
      "kotlin.Int" -> "java/lang/Integer"
      "kotlin.Long" -> "java/lang/Long"
      "kotlin.Float" -> "java/lang/Float"
      "kotlin.Double" -> "java/lang/Double"
      "kotlin.String" -> "java/lang/String"
      "kotlin.Any" -> "java/lang/Object"
      "kotlin.collections.Collection", "kotlin.collections.MutableCollection" -> "java/util/Collection"
      "kotlin.collections.List", "kotlin.collections.MutableList" -> "java/util/List"
      "kotlin.collections.Set", "kotlin.collections.MutableSet" -> "java/util/Set"
      "kotlin.collections.Map", "kotlin.collections.MutableMap" -> "java/util/Map"
      "kotlin.BooleanArray" -> return "[Z"
      "kotlin.ByteArray" -> return "[B"
      "kotlin.CharArray" -> return "[C"
      "kotlin.ShortArray" -> return "[S"
      "kotlin.IntArray" -> return "[I"
      "kotlin.LongArray" -> return "[J"
      "kotlin.FloatArray" -> return "[F"
      "kotlin.DoubleArray" -> return "[D"
      else -> return null
    }
  return "L$owner;"
}

internal fun canvasParameters(
  function: IrFunction,
  id: String,
): List<ContractParameter> =
  function.parameters.filter {
    it.kind == IrParameterKind.Regular && isCanvasType(it.type)
  }.map { ContractParameter(id, it.name.asString(), ParameterKind.CANVAS) }

internal fun isCanvasType(type: IrType): Boolean =
  type.classFqName?.asString() == "org.buildmosaic.core.injection.Canvas"

internal fun isCapabilityType(type: IrType): Boolean =
  type.classFqName?.asString() in
    setOf(
      "org.buildmosaic.core.injection.Canvas",
      "org.buildmosaic.core.injection.CanvasBuilder", "org.buildmosaic.core.injection.CanvasFactory",
      "org.buildmosaic.core.Mosaic", "org.buildmosaic.core.Tile", "org.buildmosaic.core.MultiTile",
      "org.buildmosaic.core.injection.CanvasKey",
    )

internal fun isSource(target: String): Boolean =
  target in
    setOf(
      "org.buildmosaic.core.source", "org.buildmosaic.core.sourceOrNull",
      "org.buildmosaic.core.injection.source", "org.buildmosaic.core.injection.sourceOrNull",
      "org.buildmosaic.core.injection.Canvas.source", "org.buildmosaic.core.injection.Canvas.sourceOrNull",
    )

internal fun isTileFactory(call: IrFunctionAccessExpression): Boolean =
  (if (call is IrCall) resolvedName(call) else call.symbol.owner.fqNameWhenAvailable?.asString().orEmpty()) in
    setOf(
      "org.buildmosaic.core.singleTile", "org.buildmosaic.core.multiTile",
      "org.buildmosaic.core.perKeyTile", "org.buildmosaic.core.chunkedMultiTile",
      "org.buildmosaic.core.Tile.<init>", "org.buildmosaic.core.MultiTile.<init>",
    )

internal fun resolvedName(call: IrCall): String {
  val function = call.symbol.owner
  val declaration = if (function.isFakeOverride) function.overriddenSymbols.firstOrNull()?.owner ?: function else function
  return declaration.fqNameWhenAvailable?.asString().orEmpty()
}

internal fun propertyId(property: IrProperty): String =
  property.fqNameWhenAvailable?.asString() ?: property.name.asString()

internal fun symbolId(function: IrFunction): String {
  val declaration = if (function is IrSimpleFunction && function.isFakeOverride) function.overriddenSymbols.firstOrNull()?.owner ?: function else function
  val fq = declaration.fqNameWhenAvailable?.asString() ?: declaration.name.asString()
  val params =
    declaration.parameters.filter {
      it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.ExtensionReceiver
    }.joinToString(",") { it.type.classFqName?.asString() ?: it.type.toString() }
  return "$fq($params)"
}

internal fun binaryLocator(
  function: IrFunction,
  file: IrFile,
): String {
  val classOwner = (function.parent as? IrClass)?.fqNameWhenAvailable?.asString()?.replace('.', '/')
  val packagePath = file.packageFqName.asString().replace('.', '/')
  val facade = File(file.fileEntry.name).nameWithoutExtension + "Kt"
  val jvmOwner = classOwner ?: listOf(packagePath, facade).filter { it.isNotBlank() }.joinToString("/")
  val parameters =
    function.parameters.filter {
      it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.ExtensionReceiver
    }.joinToString(
      "",
    ) { jvmType(it.type) }
  val suspendCall = (function as? IrSimpleFunction)?.isSuspend == true
  val continuation = if (suspendCall) "Lkotlin/coroutines/Continuation;" else ""
  val returnType =
    if (function is IrConstructor) {
      "V"
    } else if (suspendCall) {
      "Ljava/lang/Object;"
    } else {
      jvmType(function.returnType)
    }
  val kotlinName = function.name.asString()
  val jvmName =
    if (kotlinName.startsWith("<get-") && kotlinName.endsWith('>')) {
      "get" + kotlinName.removePrefix("<get-").removeSuffix(">").replaceFirstChar { it.uppercase() }
    } else {
      kotlinName
    }
  return "$jvmOwner#$jvmName($parameters$continuation)$returnType"
}

internal fun jvmType(type: IrType): String =
  when (val name = type.classFqName?.asString()) {
    "kotlin.Unit" -> "V"
    "kotlin.Boolean" -> "Z"
    "kotlin.Byte" -> "B"
    "kotlin.Char" -> "C"
    "kotlin.Short" -> "S"
    "kotlin.Int" -> "I"
    "kotlin.Long" -> "J"
    "kotlin.Float" -> "F"
    "kotlin.Double" -> "D"
    "kotlin.String" -> "Ljava/lang/String;"
    "kotlin.Any" -> "Ljava/lang/Object;"
    "kotlin.collections.List", "kotlin.collections.MutableList" -> "Ljava/util/List;"
    "kotlin.collections.Set", "kotlin.collections.MutableSet" -> "Ljava/util/Set;"
    "kotlin.collections.Map", "kotlin.collections.MutableMap" -> "Ljava/util/Map;"
    null -> "Ljava/lang/Object;"
    else -> "L${name.replace('.', '/')};"
  }

internal fun IrFunctionAccessExpression.argument(name: String): IrExpression? =
  symbol.owner.parameters.firstOrNull {
    it.kind == IrParameterKind.Regular && it.name.asString() == name
  }?.let { arguments[it] }
