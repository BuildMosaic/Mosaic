package org.buildmosaic.gradle

import org.buildmosaic.analysis.ANALYSIS_KOTLIN_VERSION
import org.buildmosaic.analysis.CompilerVersionAdmission
import org.buildmosaic.analysis.CoreAnalysisRevision
import org.buildmosaic.analysis.SourceShardCodec
import org.buildmosaic.analysis.SourceShardPaths
import org.buildmosaic.analysis.SummaryCodec
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/** Assembles only current Kotlin sources. Kotlin's own compilation owns extraction and invalidation. */
@CacheableTask
abstract class ExtractMosaicTask : DefaultTask() {
  @get:Internal abstract val sources: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val javaSources: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val shardFiles: ConfigurableFileCollection

  @get:Internal abstract val dependencySummaries: ConfigurableFileCollection

  @get:Input
  val dependencyCoreRevisions: List<Int>
    get() =
      MosaicDependencyReader.metadata(
        dependencySummaries,
      ).flatMap { it.coreAnalysisRevisions }.distinct().sorted()

  @get:Internal abstract val shardDirectory: DirectoryProperty

  @get:Classpath abstract val additionalCompilerPlugins: ConfigurableFileCollection

  @get:Classpath abstract val defaultCompilerArtifacts: ConfigurableFileCollection

  @get:Classpath abstract val friendPaths: ConfigurableFileCollection

  @get:Input abstract val mosaicVersion: Property<String>

  @get:Input abstract val moduleId: Property<String>

  @get:Input abstract val productionCompilerVersion: Property<String>

  @get:Input abstract val unsupportedCompilerOptions: ListProperty<String>

  @get:Input abstract val unsupportedProjectPlugins: ListProperty<String>

  @get:Input abstract val expectedJavaVersion: Property<String>

  @get:Input abstract val selectedJavaVersion: Property<String>

  @get:Internal abstract val supportedSourceRoot: Property<String>

  @get:Internal abstract val coreCompileArtifacts: ConfigurableFileCollection

  @get:Internal abstract val coreRuntimeArtifacts: ConfigurableFileCollection

  @get:Input
  val coreAnalysisRevision: Int
    get() = CoreAnalysisRevision.selectedContext(coreCompileArtifacts.files, coreRuntimeArtifacts.files)

  @get:OutputFile abstract val summaryFile: RegularFileProperty

  @get:Input
  val sourceLayout: List<String>
    get() {
      val root = File(supportedSourceRoot.get()).canonicalFile.toPath()
      return sources.files.map { source ->
        val path = source.canonicalFile.toPath()
        if (path.startsWith(root)) {
          SourceShardPaths.sourceId(File(supportedSourceRoot.get()), source)
        } else {
          "<unsupported>:${source.name}"
        }
      }.sorted()
    }

  @TaskAction
  @Suppress("TooGenericExceptionCaught")
  fun extract() {
    val output = summaryFile.get().asFile
    output.delete()
    validateConfiguration()
    validateSources()
    val current = sourceLayout.filter { it.endsWith(".kt") }
    requireSupported(current.size == current.distinct().size, "Duplicate Mosaic source identities")
    val root = shardDirectory.get().asFile
    val shards =
      current.map { sourceId ->
        val shard = SourceShardPaths.shardFile(root, sourceId)
        if (!shard.isFile) throw GradleException("Missing Mosaic compiler shard for current source $sourceId")
        try {
          SourceShardCodec.decode(shard.readBytes()).also {
            require(it.sourceId == sourceId) { "Mosaic shard identity mismatch for $sourceId" }
          }
        } catch (error: Exception) {
          throw GradleException("Invalid Mosaic compiler shard for $sourceId", error)
        }
      }
    val requirements = dependencyCoreRevisions.toSet()
    val bytes =
      SourceShardCodec.assemble(
        moduleId.get(),
        shards,
        coreAnalysisRevision,
        requirements,
        compilerVersion = productionCompilerVersion.get(),
      )
    SummaryCodec.decode(bytes)
    output.parentFile.mkdirs()
    val pending = File(output.parentFile, "${output.name}.pending")
    pending.writeBytes(bytes)
    Files.move(pending.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  private fun validateSources() {
    requireSupported(
      sources.files.none { it.extension == "kts" },
      "Mosaic extraction does not support Kotlin scripts",
    )
    requireSupported(
      javaSources.files.isEmpty(),
      "Mosaic extraction does not support mixed Java/Kotlin sources",
    )
    requireSupported(
      sourceLayout.none {
        it.startsWith("<unsupported>:")
      },
      "Mosaic extraction supports only src/main/kotlin sources",
    )
  }

  private fun validateConfiguration() {
    val compilerApi = CompilerVersionAdmission.compilerApi(productionCompilerVersion.get())
    val compilerPlugins = additionalCompilerPlugins.files.filter(::isMosaicCompilerArtifact)
    requireSupported(compilerPlugins.size == 1, "Mosaic compiler plugin artifact was not resolved exactly once")
    validateCompilerPluginVersion(compilerPlugins.single(), mosaicVersion.get())
    val artifactApi =
      JarFile(
        compilerPlugins.single(),
      ).use { it.manifest?.mainAttributes?.getValue("Mosaic-Compiler-API") }
    requireSupported(
      artifactApi == compilerApi,
      "Mosaic compiler API mismatch: Kotlin ${productionCompilerVersion.get()} requires $compilerApi, " +
        "artifact is $artifactApi",
    )
    val unsupported =
      additionalCompilerPlugins.files.filterNot {
        it in compilerPlugins || it in defaultCompilerArtifacts.files
      }
    requireSupported(
      unsupported.isEmpty(),
      "Mosaic extraction does not support additional Kotlin compiler plugins: ${unsupported.joinToString { it.name }}",
    )
    requireSupported(friendPaths.files.isEmpty(), "Mosaic extraction does not support Kotlin friend paths")
    requireSupported(
      unsupportedCompilerOptions.get().isEmpty(),
      "Mosaic extraction does not support compiler options: ${unsupportedCompilerOptions.get().joinToString()}",
    )
    requireSupported(
      unsupportedProjectPlugins.get().isEmpty(),
      "Mosaic extraction does not support project plugins: ${unsupportedProjectPlugins.get().joinToString()}",
    )
    requireSupported(
      expectedJavaVersion.get() == selectedJavaVersion.get(),
      "Mosaic extraction toolchain mismatch",
    )
  }
}

internal fun validateCompilerPluginVersion(
  jar: File,
  installedVersion: String,
) {
  val artifactVersion = JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Implementation-Version") }
  requireSupported(
    artifactVersion == installedVersion,
    "Mosaic compiler plugin version mismatch: installed Gradle plugin is $installedVersion, " +
      "compiler artifact is ${artifactVersion ?: "unversioned"}",
  )
}

private fun requireSupported(
  condition: Boolean,
  reason: String,
) {
  if (!condition) throw GradleException(reason)
}

private fun isMosaicCompilerArtifact(file: File): Boolean =
  file.isFile && file.extension == "jar" &&
    JarFile(file).use { archive ->
      archive.getJarEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar")?.let {
        archive.getInputStream(it).bufferedReader().use { reader ->
          reader.lineSequence().any { line -> line.trim() == "org.buildmosaic.compiler.MosaicCompilerRegistrar" }
        }
      } == true
    }

internal fun isDefaultKotlinCompilerArtifact(
  id: ModuleComponentIdentifier,
  kotlinPluginVersion: String,
): Boolean =
  (id.group == "org.jetbrains" && id.module == "annotations" && id.version == "13.0") ||
    // The default introspector's published component also selects the host stdlib.
    (
      id.group == "org.jetbrains.kotlin" && id.module == "kotlin-stdlib" &&
        id.version in setOf(kotlinPluginVersion, ANALYSIS_KOTLIN_VERSION)
    ) ||
    (
      id.group == "org.jetbrains.kotlin" && id.version == kotlinPluginVersion && id.module in
        setOf(
          "kotlin-scripting-compiler-embeddable",
          "kotlin-scripting-compiler-impl-embeddable",
          "kotlin-scripting-jvm",
          "kotlin-scripting-common",
          "kotlin-stdlib",
          "kotlin-script-runtime",
        )
    )
