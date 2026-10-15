package org.buildmosaic.compiler

import org.buildmosaic.analysis.CompilerVersionAdmission
import org.buildmosaic.analysis.CoreAnalysisRevision
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.io.File
import java.util.jar.JarFile

internal val outputKey = CompilerConfigurationKey<String>("Mosaic analysis output")
internal val moduleKey = CompilerConfigurationKey<String>("Mosaic analysis module identity")
internal val probeKey = CompilerConfigurationKey<String>("Mosaic IR shape probe")
internal val sourceRootKey = CompilerConfigurationKey<String>("Mosaic supported source root")
internal val revisionKey = CompilerConfigurationKey<String>("Mosaic selected Core semantic revision")
internal val compilerVersionKey = CompilerConfigurationKey<String>("Mosaic selected compiler version")
internal val modeKey = CompilerConfigurationKey<String>("Mosaic output mode")

@OptIn(ExperimentalCompilerApi::class)
class MosaicCommandLineProcessor : CommandLineProcessor {
  override val pluginId = "org.buildmosaic.analysis"
  override val pluginOptions: Collection<AbstractCliOption> =
    listOf(
      CliOption(
        "output",
        "Complete summary output path",
        "Output path",
        required = true,
        allowMultipleOccurrences = false,
      ),
      CliOption(
        "module",
        "Module identity",
        "Module ID",
        required = true,
        allowMultipleOccurrences = false,
      ),
      CliOption(
        "probe",
        "Write raw IR phase-zero facts",
        "Probe path",
        required = false,
        allowMultipleOccurrences = false,
      ),
      CliOption(
        "sourceRoot",
        "Supported main Kotlin source root",
        "Source root",
        required = false,
        allowMultipleOccurrences = false,
      ),
      CliOption(
        "coreRevision",
        "Internal semantic compilation input",
        "Core revision",
        required = false,
        allowMultipleOccurrences = false,
      ),
      CliOption(
        "compilerVersion",
        "Internal selected compiler",
        "Compiler version",
        required = false,
        allowMultipleOccurrences = false,
      ),
      CliOption("mode", "Mosaic output mode", "complete or shards", required = false, allowMultipleOccurrences = false),
    )

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration,
  ) {
    when (option.optionName) {
      "output" -> configuration.put(outputKey, value)
      "module" -> configuration.put(moduleKey, value)
      "probe" -> configuration.put(probeKey, value)
      "sourceRoot" -> configuration.put(sourceRootKey, value)
      "coreRevision" -> configuration.put(revisionKey, value)
      "mode" -> configuration.put(modeKey, value)
      "compilerVersion" -> configuration.put(compilerVersionKey, value)
      else -> error("Unknown Mosaic compiler option ${option.optionName}")
    }
  }
}

@OptIn(ExperimentalCompilerApi::class)
class MosaicCompilerRegistrar : CompilerPluginRegistrar() {
  override val pluginId = "org.buildmosaic.analysis"
  override val supportsK2 = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    val compilerApi =
      JarFile(File(MosaicCompilerRegistrar::class.java.protectionDomain.codeSource.location.toURI())).use {
        requireNotNull(it.manifest?.mainAttributes?.getValue("Mosaic-Compiler-API")) {
          "Mosaic introspector is missing its compiler API identity"
        }
      }
    CompilerVersionAdmission.requireSupported(KotlinCompilerVersion.VERSION, compilerApi)
    configuration.get(compilerVersionKey)?.let { expected ->
      require(
        CompilerVersionAdmission.stableVersion(expected) ==
          CompilerVersionAdmission.stableVersion(KotlinCompilerVersion.VERSION),
      ) {
        "Mosaic selected Kotlin compiler $expected but executing compiler is ${KotlinCompilerVersion.VERSION}"
      }
    }
    val revision =
      requireNotNull(
        CoreAnalysisRevision.selected(
          configuration.getList(
            CLIConfigurationKeys.CONTENT_ROOTS,
          ).filterIsInstance<JvmClasspathRoot>().map { it.file },
        ),
      )
    configuration.get(revisionKey)?.let { expected ->
      require(expected == revision.toString()) {
        "Mosaic Core revision compiler input $expected disagrees with actual selected Core revision $revision"
      }
    }
    val mode = configuration.get(modeKey) ?: "complete"
    require(mode == "complete" || mode == "shards") { "Unsupported Mosaic compiler output mode $mode" }
    if (mode == "shards") requireNotNull(configuration.get(sourceRootKey)) { "Mosaic shards require a source root" }
    IrGenerationExtension.registerExtension(
      MosaicIrExtractor(
        requireNotNull(configuration.get(outputKey)),
        requireNotNull(configuration.get(moduleKey)),
        configuration.get(sourceRootKey),
        mode == "shards",
        revision,
      ),
    )
    configuration.get(probeKey)?.let { IrGenerationExtension.registerExtension(MosaicIrProbe(it)) }
  }
}
