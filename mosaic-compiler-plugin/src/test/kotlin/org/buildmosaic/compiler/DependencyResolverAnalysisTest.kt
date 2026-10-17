package org.buildmosaic.compiler

import org.buildmosaic.analysis.AnalysisPolicy
import org.buildmosaic.analysis.AnalysisRequest
import org.buildmosaic.analysis.Certainty
import org.buildmosaic.analysis.FindingKind
import org.buildmosaic.analysis.MosaicAnalyzer
import org.buildmosaic.analysis.SelectedRoot
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength")
class DependencyResolverAnalysisTest {
  @Test
  fun `dynamic fallback uses existing uncertainty rather than claiming missing or proven dependencies`() {
    val directory = Files.createTempDirectory("mosaic-resolver-analysis").toFile()
    try {
      val source =
        File(directory, "Resolver.kt").apply {
          writeText(
            """
            package resolver
            import org.buildmosaic.core.injection.*
            import org.buildmosaic.core.singleTile
            import org.buildmosaic.core.source
            val dynamicTile = singleTile { source<String>() }
            val localTile = singleTile { source<Int>() }
            val fallback = object : CanvasDependencyResolver {
              override fun <T : Any> resolve(key: CanvasKey<T>): T? = null
            }
            suspend fun dynamic() {
              val root = canvas { dependencyResolver(fallback); instance(42) }
              root.withMosaic { compose(dynamicTile); compose(localTile) }
            }
            suspend fun child() {
              val root = canvas { dependencyResolver(fallback) }
              root.withLayer { instance(42) }.withMosaic { compose(dynamicTile); compose(localTile) }
            }
            """.trimIndent(),
          )
        }
      val module = compileAndExtract(listOf(source), File(directory, "output"), "resolver")
      for (root in listOf("dynamic", "child")) {
        val report =
          MosaicAnalyzer().analyze(
            AnalysisRequest(
              module,
              roots = listOf(SelectedRoot(root, "resolver.$root()")),
              policy = AnalysisPolicy.STRICT,
            ),
          )
        assertTrue(report.findings.any { it.certainty == Certainty.UNVERIFIED }, report.toString())
        assertTrue(report.findings.none { it.certainty == Certainty.MISSING }, report.toString())
        assertTrue(
          report.findings.any {
            it.kind == FindingKind.REQUIRED_LOOKUP && it.key?.classId == "kotlin.String" &&
              it.certainty == Certainty.UNVERIFIED
          },
          report.toString(),
        )
        assertTrue(
          report.findings.any {
            it.kind == FindingKind.REQUIRED_LOOKUP && it.key?.classId == "kotlin.Int" &&
              it.certainty == Certainty.VERIFIED
          },
          report.toString(),
        )
      }
    } finally {
      directory.deleteRecursively()
    }
  }
}
