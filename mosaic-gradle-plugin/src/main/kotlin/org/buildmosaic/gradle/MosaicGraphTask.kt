package org.buildmosaic.gradle

import org.buildmosaic.analysis.AnalysisPolicy
import org.buildmosaic.analysis.AnalysisRequest
import org.buildmosaic.analysis.ModuleContract
import org.buildmosaic.analysis.MosaicGraph
import org.buildmosaic.analysis.MosaicRuleSeverity
import org.buildmosaic.analysis.RootSelectionResolver
import org.buildmosaic.analysis.SummaryCodec
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.IOException

/** Renders contract structure and optional root findings without applying verification policy. */
abstract class MosaicGraphTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val summaryFile: RegularFileProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val dependencySummaries: ConfigurableFileCollection

  @get:Internal
  abstract val dependencyArtifacts: ConfigurableFileCollection

  @get:Input
  val unsupportedDependencyArtifacts: List<String>
    get() = dependencyArtifacts.files.filter { !it.isFile || it.extension != "jar" }.map { it.name }.sorted()

  @get:Input abstract val roots: ListProperty<String>

  @get:Input abstract val role: Property<MosaicAnalysisRole>

  @get:Input abstract val enforcement: Property<MosaicAnalysisEnforcement>

  @get:Input
  abstract val ruleSeverities: MapProperty<String, MosaicRuleSeverity>

  @get:OutputFile abstract val graphFile: RegularFileProperty

  @TaskAction fun generate() {
    val output = graphFile.get().asFile
    output.delete()
    val selectedRoots = roots.get()
    validateRoots(selectedRoots)
    validateArtifacts()
    val program = readLocalSummary()
    val dependencies = MosaicDependencyReader.read(dependencySummaries)
    val modules = listOf(program) + dependencies
    requireUniqueSelectedOwners(modules)
    val selected =
      if (role.get() == MosaicAnalysisRole.LIBRARY) {
        emptyList()
      } else {
        try {
          RootSelectionResolver.resolve(program, dependencies, selectedRoots)
        } catch (failure: IllegalArgumentException) {
          throw GradleException(failure.message ?: "Mosaic root selection failed", failure)
        }
      }
    val policy =
      if (enforcement.get() == MosaicAnalysisEnforcement.STRICT) {
        AnalysisPolicy.STRICT
      } else {
        AnalysisPolicy.DEFAULT
      }
    val document =
      MosaicGraph.render(
        AnalysisRequest(program, dependencies, selected, policy = policy, ruleSeverities = ruleSeverities.get()),
      )
    output.parentFile.mkdirs()
    output.writeText(document)
  }

  private fun validateRoots(selectedRoots: List<String>) {
    if (selectedRoots.any { it.isBlank() }) throw GradleException("Mosaic graph roots must not be blank")
    if (role.get() == MosaicAnalysisRole.LIBRARY && selectedRoots.isNotEmpty()) {
      throw GradleException("Mosaic LIBRARY cannot select application roots")
    }
  }

  private fun validateArtifacts() {
    if (unsupportedDependencyArtifacts.isNotEmpty()) {
      throw GradleException(
        "Mosaic graph requires dependency JARs, not class directories or other artifacts: " +
          unsupportedDependencyArtifacts.joinToString(),
      )
    }
  }

  private fun readLocalSummary(): ModuleContract =
    try {
      val summary = SummaryCodec.decode(summaryFile.get().asFile.readBytes())
      if (!summary.complete) throw GradleException("Local Mosaic summary is partial")
      summary.module
    } catch (failure: RuntimeException) {
      throw GradleException("Invalid local Mosaic summary: ${failure.message}", failure)
    } catch (failure: IOException) {
      throw GradleException("Invalid local Mosaic summary: ${failure.message}", failure)
    }
}
