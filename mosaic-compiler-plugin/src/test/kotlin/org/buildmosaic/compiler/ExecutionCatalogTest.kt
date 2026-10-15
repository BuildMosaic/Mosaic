@file:Suppress("FunctionMaxLength", "LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.compiler

import org.buildmosaic.analysis.AnalysisPolicy
import org.buildmosaic.analysis.AnalysisRequest
import org.buildmosaic.analysis.CanvasExpression
import org.buildmosaic.analysis.Certainty
import org.buildmosaic.analysis.FindingKind
import org.buildmosaic.analysis.MosaicAnalyzer
import org.buildmosaic.analysis.RootStatus
import org.buildmosaic.analysis.SelectedRoot
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One compilation, independently selected roots. See SCENARIOS.md for the fixed boundary. */
@Suppress("LargeClass") // Keep independently named assertions on one shared compiler fixture.
class ExecutionCatalogTest {
  private fun report(name: String) =
    MosaicAnalyzer().analyze(
      AnalysisRequest(
        module,
        roots = listOf(SelectedRoot(name, "catalog.${if ('(' in name) name else "$name()"}")),
        policy = AnalysisPolicy.STRICT,
      ),
    ).also { report ->
      assertTrue(
        report.roots.single().specializedContracts.contains("catalog.${if ('(' in name) name else "$name()"}"),
        report.toString(),
      )
      assertTrue(report.findings.none { it.reason.contains("Selected root target is missing") }, report.toString())
    }

  private fun verified(name: String) {
    val report = report(name)
    assertEquals(RootStatus.VERIFIED, report.roots.single().status, "$name: $report")
    assertTrue(report.policyDecision.passed, "$name: $report")
  }

  private fun missing(
    name: String,
    key: String = "Metrics",
  ) {
    val report = report(name)
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "catalog.$key"
      },
      "$name: $report",
    )
    assertTrue(!report.policyDecision.passed)
  }

  private fun unknown(
    name: String,
    boundary: String,
  ) {
    val report = report(name)
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains(boundary, ignoreCase = true)
      },
      "$name: $report",
    )
    assertTrue(!report.policyDecision.passed)
  }

  @Test fun `A1 ignored ordinary argument missing and satisfied`() {
    missing("ignoredMissing")
    verified("ignoredSatisfied")
  }

  @Test fun `A2 receiver named vararg and spread work retain order`() {
    val report = report("ordered")
    assertEquals(
      listOf("Receiver", "Second", "First", "Spread"),
      report.findings.filter {
        it.certainty == Certainty.MISSING
      }.map { it.key?.classId?.substringAfterLast('.') },
    )
  }

  @Test fun `A4 literal defaults and supplied constructor actuals verify`() {
    verified("literalDefaults")
    verified("explicitDefaults")
  }

  @Test fun `A5 inline ordinary and Canvas getters cannot evade boundary`() {
    unknown("inlineOrdinary", "inline")
    unknown("inlineCanvas", "inline")
  }

  @Test fun `A5 setter actual effects and inline setter share call eligibility`() {
    missing("setterMissing")
    verified("setterSatisfied")
    unknown("inlineSetter", "inline")
  }

  @Test fun `A6 getter block prefix matches ordinary function prefix`() {
    val getter = report("getterPrefix")
    assertTrue(
      getter.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.key?.classId == "catalog.Metrics"
      },
      getter.toString(),
    )
    missing("functionPrefix")
    missing("discardedFactory")
  }

  @Test fun `A6 definite prefix abort prevents Canvas result construction`() {
    val report = report("abortResult")
    missing("abortResult", "First")
    assertTrue(report.findings.none { it.key?.classId == "catalog.Second" }, report.toString())
  }

  @Test fun `A6 empty successful and unknown prefixes continue`() {
    verified("emptyPrefix")
    verified("successfulPrefix")
    unknown("unknownPrefix", "callback")
    missing("unknownPrefix", "Second")
  }

  @Test fun `B3 virtual Canvas method and getter do not adopt base bodies`() {
    unknown("virtualMethod", "Virtual")
    unknown("virtualGetter", "Virtual")
    verified("finalFactory")
  }

  @Test fun `C1 immutable Canvas and Mosaic aliases construct once`() {
    val report = report("aliases")
    assertEquals(1, report.findings.count { it.key?.classId == "catalog.Metrics" }, report.toString())
    missing("aliases")
  }

  @Test fun `C4 member dependent Tile is not a stable global declaration`() {
    unknown("memberTile", "Tile")
  }

  @Test fun `C4 Tile getter retains creation arguments`() {
    val report = report("sizedTile")
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.key?.classId == "kotlin.Int"
      },
      report.toString(),
    )
  }

  @Test fun `C5 unused lambda creation does not execute body`() {
    verified("unusedLambda")
  }

  @Test fun `C5 lambda and callable reference invocation stay unknown`() {
    unknown("invokedLambda", "callable")
    unknown("invokedReference", "callable")
    unknown("escapedLambda", "callback")
    unknown("escapedReturnedLambda", "callback")
    unknown("conditionalCallableEscape", "callback")
  }

  @Test fun `D4 registration actual work precedes provider construction`() {
    missing("registrationArgument")
    unknown("foreignRegistration", "registration receiver")
    unknown("foreignConstruction", "CanvasFactory receiver")
  }

  @Test fun `D5 optional argument effects remain required work`() {
    missing("optionalArgument")
  }

  @Test fun `E2 object delegated and super initialization are explicit boundaries`() {
    unknown("objectAccess", "initialization")
    unknown("delegatedAccess", "delegat")
    unknown("superInit", "initialization")
  }

  @Test fun `F6 unsupported controls stay localized and harmless controls verify`() {
    listOf("conditional", "loop", "tryFinally", "safeElvis", "mutation", "conditionalDefault").forEach { name ->
      unknown(name, "Unsupported")
      assertTrue(report(name).findings.none { it.certainty == Certainty.MISSING }, report(name).toString())
    }
    verified("harmlessControl")
  }

  @Test fun `F6 structural equality cannot omit user equals`() {
    assertTrue(module.callables.single { it.id == "catalog.CallbackValue.<init>()" }.effects.isEmpty())
    unknown("structuralEquality", "Implicit equals")
  }

  @Test fun `F6 equality inside control flow remains localized`() {
    unknown("conditionalEquality(catalog.CallbackValue,catalog.CallbackValue)", "Unsupported control flow")
  }

  @Test fun `C5 bound reference evaluates receiver inside conditional`() {
    val name = "boundReference(kotlin.Boolean)"
    unknown(name, "Unsupported control flow")
    assertTrue(report(name).findings.none { it.certainty == Certainty.MISSING })
  }

  @Test fun `C5 directly bound receiver retains construction failure`() {
    missing("directBoundReference")
    val finding = report("directBoundReference").findings.single { it.key?.classId == "catalog.Metrics" }
    assertEquals(FindingKind.CONSTRUCTION_LOOKUP, finding.kind)
  }

  @Test fun `C5 unused lambda and unbound references defer their bodies`() {
    verified("deferredCreation(kotlin.Boolean)")
  }

  @Test fun `F6 scalar equality and callback free collection controls verify`() {
    verified("primitiveEquality(kotlin.Int,kotlin.Int)")
    verified("stringEquality(kotlin.String,kotlin.String)")
    verified("harmlessControl")
    verified("safeCollections")
    verified("safeMaps(kotlin.Array)")
    verified("safeSetSpread(kotlin.Array)")
    verified("conditionalScalars(kotlin.Array)")
    verified("errorConstant")
  }

  @Test fun `F6 set construction cannot omit user hashing`() {
    unknown("setCallbacks", "Implicit hashCode or equals")
  }

  @Test fun `F6 mutable set construction cannot omit user hashing`() {
    unknown("mutableSetCallbacks", "Implicit hashCode or equals")
  }

  @Test fun `F6 map construction cannot omit user key hashing`() {
    unknown("mapCallbacks(kotlin.Array)", "Implicit hashCode or equals")
  }

  @Test fun `F6 mutable map construction cannot omit user key hashing`() {
    unknown("mutableMapCallbacks(kotlin.Array)", "Implicit hashCode or equals")
  }

  @Test fun `F6 opaque set elements do not prove harmless hashing`() {
    unknown("opaqueSet(kotlin.Array)", "Implicit hashCode or equals")
  }

  @Test fun `F6 structural omission shares set eligibility`() {
    unknown("conditionalSet(kotlin.Array)", "Unsupported control flow")
  }

  @Test fun `F6 structural omission shares map eligibility`() {
    unknown("conditionalMap(kotlin.Array)", "Unsupported control flow")
  }

  @Test fun `F6 structural omission shares message conversion eligibility`() {
    unknown("conditionalError(catalog.CallbackValue)", "Unsupported control flow")
  }

  @Test fun `F6 error message cannot omit user toString`() {
    unknown("errorCallback", "Implicit toString")
  }

  @Test fun `F6 structural proof respects mutable capability alias boundary`() {
    unknown("mutableAlias(kotlin.Boolean,org.buildmosaic.core.injection.Canvas)", "Unsupported control flow")
  }

  @Test fun `C5 structural proof rejects reference escape`() {
    unknown("conditionalReferenceEscape", "Unsupported control flow")
    unknown("conditionalReferenceAlias", "Unsupported control flow")
  }

  @Test fun `B2 structural proof respects extension receiver eligibility`() {
    unknown("conditionalExtension(org.buildmosaic.core.Mosaic)", "Unsupported control flow")
  }

  @Test fun `F6 field receiver evaluation is retained`() {
    unknown("conditionalField(kotlin.Boolean)", "Unsupported control flow")
    missing("directField")
  }

  @Test fun `F6 structural proof respects callable field escape boundary`() {
    unknown("CallbackField.<set-callback>(kotlin.Function0)", "Unsupported control flow")
  }

  @Test fun `B1 captured Tile receiver cannot borrow inner registration`() {
    missing("capturedEntry")
    val lookup = report("capturedEntry").findings.single { it.key?.classId == "catalog.Metrics" }
    assertEquals(FindingKind.REQUIRED_LOOKUP, lookup.kind)
    assertEquals("catalog.CapturedTile", lookup.site.owner)
    assertEquals(null, lookup.bindingSite)
  }

  @Test fun `B1 explicitly labeled Tile receiver keeps its original Canvas`() {
    missing("capturedLabelEntry")
  }

  @Test fun `B1 captured Tile receiver retains outer provider provenance`() {
    verified("capturedSuppliedEntry")
    val lookup = report("capturedSuppliedEntry").findings.single { it.key?.classId == "catalog.Metrics" }
    assertEquals("catalog.suppliedOuter()", lookup.bindingSite?.owner, lookup.toString())
    assertTrue(lookup.canvasPath.none { it.site?.owner == "catalog.CapturedTile" }, lookup.toString())
  }

  @Test fun `C1 receiver aliases and captures do not reconstruct Canvas`() {
    verified("capturedOnceEntry")
    val lookups = report("capturedOnceEntry").findings.filter { it.key?.classId == "catalog.First" }
    assertEquals(1, lookups.size, lookups.toString())
    assertEquals(FindingKind.CONSTRUCTION_LOOKUP, lookups.single().kind)
    assertEquals("catalog.outerWithConstruction()", lookups.single().site.owner)
  }

  @Test fun `D2 captured CanvasFactory receiver cannot borrow nested registration`() {
    missing("nestedFactoryMissing")
    val lookup = report("nestedFactoryMissing").findings.single { it.key?.classId == "catalog.Metrics" }
    assertEquals(FindingKind.CONSTRUCTION_LOOKUP, lookup.kind)
    assertEquals(null, lookup.bindingSite)
  }

  @Test fun `D2 nested source uses own layer while captured factory uses original layer`() {
    verified("nestedFactorySupplied")
    val layer =
      module.canvases.single {
        it.id == "catalog.nestedFactorySupplied()"
      }.result.evaluatedResult() as CanvasExpression.Layer
    val findings = report("nestedFactorySupplied").findings
    val captured = findings.single { it.key?.classId == "catalog.Metrics" }
    val own = findings.single { it.key?.classId == "catalog.Second" }
    assertEquals(layer.bindings.first().site, captured.bindingSite, captured.toString())
    assertEquals(layer.id, captured.canvasPath.first().layerId, captured.toString())
    assertTrue(own.bindingSite != captured.bindingSite, own.toString())
    assertTrue(own.canvasPath.first().layerId != layer.id, own.toString())
  }

  @Test fun `B1 direct Tile source and immutable stable Tile alias stay supported`() {
    missing("directNeedsEntry")
    verified("directNeedsSupplied")
    verified("stableTileAlias")
  }

  @Test fun `C4 computed getter cannot export initializer Tile as its result`() {
    unknown("computedEntry", "computed Tile property")
    assertTrue(module.tiles.none { it.id == "catalog.ExposedTile" }, module.toString())
  }

  @Test fun `C4 computed getter retains evaluated getter work`() {
    val finding = report("computedWorkEntry").findings.single { it.key?.classId == "catalog.Second" }
    assertEquals(FindingKind.REQUIRED_LOOKUP, finding.kind)
    assertEquals("catalog.<get-ComputedWorkTile>()", finding.site.owner)
    unknown("computedWorkEntry", "computed Tile property")
  }

  @Test fun `C4 computed getter retains evaluated creation work`() {
    val finding = report("computedWorkEntry").findings.single { it.key?.classId == "kotlin.Int" }
    assertEquals(FindingKind.REQUIRED_LOOKUP, finding.kind)
    assertEquals(Certainty.UNVERIFIED, finding.certainty)
  }

  companion object {
    private val module by lazy {
      val directory = Files.createTempDirectory("mosaic-execution-catalog").toFile()
      val source = File(directory, "Catalog.kt")
      source.writeText(
        requireNotNull(ExecutionCatalogTest::class.java.getResource("/fixtures/execution-catalog.kt")).readText(),
      )
      compileAndExtract(listOf(source), directory, "catalog")
    }
  }
}
