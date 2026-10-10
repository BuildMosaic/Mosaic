@file:Suppress("LargeClass")
@file:OptIn(
  org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class,
  org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class,
)

package org.buildmosaic.gradle

import org.buildmosaic.analysis.CompilerVersionAdmission
import org.buildmosaic.analysis.CoreAnalysisRevision
import org.buildmosaic.analysis.MosaicRule
import org.buildmosaic.analysis.MosaicRuleSeverity
import org.buildmosaic.analysis.SourceShardPaths
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties
import javax.inject.Inject

private const val MOSAIC_SUMMARY_ARTIFACT_TYPE = "mosaic-analysis-summary"

abstract class MosaicAnalysisExtension
  @Inject
  constructor(objects: ObjectFactory) {
    val rules = MosaicAnalysisRules(objects)

    fun rules(action: Action<MosaicAnalysisRules>) = action.execute(rules)

    val roots: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())
    val role: Property<MosaicAnalysisRole> =
      objects.property(MosaicAnalysisRole::class.java).convention(MosaicAnalysisRole.APPLICATION)
    val enforcement: Property<MosaicAnalysisEnforcement> =
      objects.property(MosaicAnalysisEnforcement::class.java).convention(MosaicAnalysisEnforcement.STANDARD)
  }

class MosaicAnalysisRules internal constructor(objects: ObjectFactory) {
  internal val severities: MapProperty<String, MosaicRuleSeverity> =
    objects.mapProperty(String::class.java, MosaicRuleSeverity::class.java).convention(emptyMap())

  fun severity(
    ruleId: String,
    severity: MosaicRuleSeverity,
  ) {
    MosaicRule.configurable(ruleId)
    severities.put(ruleId, severity)
  }
}

enum class MosaicAnalysisRole {
  APPLICATION,
  LIBRARY,
}

enum class MosaicAnalysisEnforcement {
  STANDARD,
  STRICT,
}

class MosaicAnalysisPlugin : KotlinCompilerPluginSupportPlugin {
  private lateinit var project: Project
  private lateinit var mosaicVersion: String
  private val coreCompileArtifacts by lazy { selectedCoreArtifacts(project, "compileClasspath") }
  private val coreRuntimeArtifacts by lazy { selectedCoreArtifacts(project, "runtimeClasspath") }
  private val dependencySummaries by lazy {
    project.configurations.getByName("compileClasspath").incoming.artifactView { view ->
      view.attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, MOSAIC_SUMMARY_ARTIFACT_TYPE)
    }.files
  }

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
    kotlinCompilation.name == "main" && kotlinCompilation.platformType == KotlinPlatformType.jvm &&
      kotlinCompilation.project.plugins.hasPlugin("org.jetbrains.kotlin.jvm")

  override fun getCompilerPluginId(): String = "org.buildmosaic.analysis"

  private val compilerVersion
    get() = project.extensions.getByType(KotlinJvmProjectExtension::class.java).compilerVersion

  override fun getPluginArtifact(): SubpluginArtifact {
    val api = CompilerVersionAdmission.compilerApi(compilerVersion.get())
    return SubpluginArtifact("org.buildmosaic", CompilerVersionAdmission.artifactId(api), mosaicVersion)
  }

  override fun applyToCompilation(
    kotlinCompilation: KotlinCompilation<*>,
  ): org.gradle.api.provider.Provider<List<SubpluginOption>> {
    val output = project.layout.buildDirectory.dir("mosaic-analysis/main/shards")
    val root = project.file("src/main/kotlin")
    val module = project.group.toString() + ":" + project.name
    val selectedCompiler = compilerVersion.get()
    val selectedRevision =
      project.providers.of(SelectedCoreRevision::class.java) {
        it.parameters.artifacts.from(coreCompileArtifacts)
      }
    return selectedRevision.map { revision ->
      listOf(
        FilesSubpluginOption("output", listOf(output.get().asFile)),
        FilesSubpluginOption("sourceRoot", listOf(root)),
        SubpluginOption("mode", "shards"),
        SubpluginOption("coreRevision", revision.toString()),
        SubpluginOption("module", module),
        SubpluginOption("compilerVersion", selectedCompiler),
      )
    }
  }

  override fun apply(target: Project) {
    val project = target
    this.project = target
    registerAnalysisTransforms(project)
    val extension = project.extensions.create("mosaicAnalysis", MosaicAnalysisExtension::class.java)
    mosaicVersion = installedMosaicVersion()
    project.afterEvaluate {
      if (!project.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
        throw GradleException("Mosaic analysis requires a pure Kotlin/JVM project")
      }
    }
    project.plugins.withId("org.jetbrains.kotlin.jvm") {
      registerMain(project, extension)
    }
  }

  private fun installedMosaicVersion(): String {
    val stream =
      javaClass.getResourceAsStream("version.properties")
        ?: throw GradleException("Mosaic analysis plugin is missing its generated version metadata")
    val properties = Properties().apply { stream.use(::load) }
    return properties.getProperty("version")?.takeIf(String::isNotBlank)
      ?: throw GradleException("Mosaic analysis plugin has invalid version metadata")
  }

  private fun registerMain(
    project: Project,
    extension: MosaicAnalysisExtension,
  ) {
    val compile = project.tasks.named("compileKotlin", KotlinJvmCompile::class.java)
    val shardDirectory = project.layout.buildDirectory.dir("mosaic-analysis/main/shards")
    compile.configure { it.outputs.dir(shardDirectory).withPropertyName("mosaicSourceShards") }
    val extract =
      registerExtraction(project, compile)
    project.tasks.named("jar", Jar::class.java) { jar ->
      jar.dependsOn(extract)
      jar.from(extract.flatMap { it.summaryFile }) {
        it.into("META-INF/mosaic-analysis/v1")
      }
    }
    val verify =
      project.tasks.register("verifyMosaicMain", VerifyMosaicTask::class.java) { task ->
        task.group = "verification"
        task.description = "Verify selected Mosaic main roots against selected dependency summaries"
        task.summaryFile.set(extract.flatMap { it.summaryFile })
        task.coreCompileArtifacts.from(coreCompileArtifacts)
        task.coreRuntimeArtifacts.from(coreRuntimeArtifacts)
        task.dependencyArtifacts.from(project.configurations.getByName("compileClasspath"))
        task.dependencySummaries.from(dependencySummaries)
        task.roots.set(extension.roots)
        task.role.set(extension.role)
        task.enforcement.set(extension.enforcement)
        task.ruleSeverities.set(extension.rules.severities)
        task.reportFile.set(project.layout.buildDirectory.file("reports/mosaic-analysis/main.txt"))
        task.dependsOn(extract)
      }
    project.tasks.register("mosaicGraph", MosaicGraphTask::class.java) { task ->
      task.group = "documentation"
      task.description = "Render local Mosaic contracts and selected root findings as a Mermaid graph"
      task.summaryFile.set(extract.flatMap { it.summaryFile })
      task.coreCompileArtifacts.from(coreCompileArtifacts)
      task.coreRuntimeArtifacts.from(coreRuntimeArtifacts)
      task.dependencyArtifacts.from(project.configurations.getByName("compileClasspath"))
      task.dependencySummaries.from(dependencySummaries)
      task.roots.set(extension.roots)
      task.role.set(extension.role)
      task.enforcement.set(extension.enforcement)
      task.ruleSeverities.set(extension.rules.severities)
      task.graphFile.set(project.layout.buildDirectory.file("reports/mosaic-analysis/graph.md"))
      task.dependsOn(extract)
    }
    val aggregate =
      project.tasks.register("verifyMosaic") { task ->
        task.group = "verification"
        task.dependsOn(verify)
      }
    project.tasks.named("check") { it.dependsOn(aggregate) }
  }

  private fun registerExtraction(
    project: Project,
    compile: TaskProvider<KotlinJvmCompile>,
  ): TaskProvider<ExtractMosaicTask> {
    val kotlinPluginVersion = (project.plugins.getPlugin("org.jetbrains.kotlin.jvm") as KotlinBasePlugin).pluginVersion
    val defaultCompilerArtifacts =
      project.configurations.named("kotlinCompilerPluginClasspathMain").flatMap { configuration ->
        configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
          artifacts.filter { artifact ->
            val id = artifact.id.componentIdentifier
            id is ModuleComponentIdentifier && isDefaultKotlinCompilerArtifact(id, kotlinPluginVersion)
          }.map { it.file }
        }
      }
    val javaExtension = project.extensions.getByType(JavaPluginExtension::class.java)
    val launcher = project.extensions.getByType(JavaToolchainService::class.java).launcherFor(javaExtension.toolchain)
    val mainSources =
      project.extensions.getByType(
        KotlinJvmProjectExtension::class.java,
      ).sourceSets.getByName("main").kotlin
    val sourceRoot = project.file("src/main/kotlin")
    val shardDirectory = project.layout.buildDirectory.dir("mosaic-analysis/main/shards")
    return project.tasks.register("extractMosaicMain", ExtractMosaicTask::class.java) { task ->
      task.group = "verification"
      task.description = "Assemble compiler-produced Mosaic source shards into a complete main summary"
      task.sources.from(mainSources)
      task.coreCompileArtifacts.from(coreCompileArtifacts)
      task.coreRuntimeArtifacts.from(coreRuntimeArtifacts)
      task.javaSources.from(project.fileTree("src/main/java") { it.include("**/*.java") })
      task.supportedSourceRoot.set(sourceRoot.absolutePath)
      task.shardFiles.from(
        mainSources.elements.map { sources ->
          sources.map { it.asFile }.filter { it.extension == "kt" }.map { source ->
            SourceShardPaths.shardFile(shardDirectory.get().asFile, SourceShardPaths.sourceId(sourceRoot, source))
          }
        },
      )
      task.additionalCompilerPlugins.from(compile.map { it.pluginClasspath })
      task.defaultCompilerArtifacts.from(defaultCompilerArtifacts)
      task.friendPaths.from(compile.map { it.friendPaths })
      task.mosaicVersion.set(mosaicVersion)
      task.moduleId.set(project.provider { project.group.toString() + ":" + project.name })
      task.productionCompilerVersion.set(compilerVersion)
      task.unsupportedCompilerOptions.set(compile.map(::unsupportedCompilerOptions))
      task.unsupportedProjectPlugins.set(project.provider { unsupportedProjectPlugins(project) })
      task.selectedJavaVersion.set(launcher.map { it.metadata.languageVersion.asInt().toString() })
      task.expectedJavaVersion.set(
        compile.flatMap {
          it.kotlinJavaToolchainProvider
        }.flatMap { it.javaVersion }.map { it.majorVersion },
      )
      task.summaryFile.set(project.layout.buildDirectory.file("mosaic-analysis/main/summary.json"))
      task.dependencySummaries.from(dependencySummaries)
      task.shardDirectory.set(shardDirectory)
      task.dependsOn(compile)
    }
  }

  private fun unsupportedCompilerOptions(taskCompile: KotlinJvmCompile): List<String> =
    buildList {
      taskCompile.pluginOptions.orNull.orEmpty().flatMap { it.allOptions().entries }.forEach { (id, options) ->
        if (options.isNotEmpty() && id != "org.buildmosaic.analysis") add("plugin:$id:${options.size}")
      }
      taskCompile.compilerOptions.freeCompilerArgs.orNull.orEmpty().forEach { add("free:$it") }
      taskCompile.compilerOptions.optIn.orNull.orEmpty().forEach { add("optIn:$it") }
      if (taskCompile.compilerOptions.progressiveMode.orNull == true) add("progressiveMode")
      if (taskCompile.compilerOptions.noJdk.orNull == true) add("noJdk")
      taskCompile.compilerOptions.jvmDefault.orNull?.let { add("jvmDefault:$it") }
      if (taskCompile.multiPlatformEnabled.orNull == true) add("multiPlatformEnabled")
    }
}

private fun unsupportedProjectPlugins(project: Project): List<String> =
  listOf(
    "org.jetbrains.kotlin.multiplatform",
    "com.android.application",
    "com.android.library",
    "com.google.devtools.ksp",
  ).filter(project.plugins::hasPlugin)

private fun registerAnalysisTransforms(project: Project) {
  project.dependencies.registerTransform(MosaicSummaryTransform::class.java) { transform ->
    transform.from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
    transform.to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, MOSAIC_SUMMARY_ARTIFACT_TYPE)
  }
}

/** Resolution selects module artifacts; opaque file dependencies use the same targeted class lookup as direct K2. */
private fun selectedCoreArtifacts(
  project: Project,
  configuration: String,
): FileCollection =
  project.configurations.getByName(configuration).incoming.artifactView { view ->
    view.componentFilter { component ->
      when (component) {
        is ModuleComponentIdentifier -> component.group == "org.buildmosaic" && component.module == "mosaic-core"
        is ProjectComponentIdentifier -> component.projectName == "mosaic-core"
        else -> true
      }
    }
  }.files

/** Reads external semantic state afresh when a configuration-cache entry is checked. */
internal abstract class SelectedCoreRevision : ValueSource<Int, SelectedCoreRevision.Parameters> {
  interface Parameters : ValueSourceParameters {
    val artifacts: ConfigurableFileCollection
  }

  override fun obtain(): Int = requireNotNull(CoreAnalysisRevision.selected(parameters.artifacts.files))
}
