package org.buildmosaic.compiler

import org.buildmosaic.analysis.SourceShardCodec
import org.buildmosaic.analysis.SourceShardPaths
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import java.io.File

/** Collects affected Kotlin sources during the normal main compilation. */
internal class MosaicIrExtractor(
  private val output: String,
  private val moduleId: String,
  private val sourceRoot: String? = null,
  private val shardMode: Boolean = false,
  private val coreAnalysisRevision: Int,
) : IrGenerationExtension {
  override fun generate(
    moduleFragment: IrModuleFragment,
    pluginContext: IrPluginContext,
  ) {
    val shards =
      moduleFragment.files.sortedBy { it.fileEntry.name }.map { file ->
        SourceContractExtractor(
          moduleId,
          sourceRoot,
          coreAnalysisRevision,
        ).extract(file).copy(compilerVersion = KotlinCompilerVersion.VERSION).also { shard ->
          if (shardMode) {
            SourceShardPaths.shardFile(File(output), shard.sourceId).apply {
              parentFile.mkdirs()
              writeBytes(SourceShardCodec.encode(shard))
            }
          }
        }
      }
    if (shardMode) {
      SourceShardStorage.pruneObsoleteShards(File(requireNotNull(sourceRoot)), File(output))
    } else {
      File(output).apply {
        parentFile.mkdirs()
        writeBytes(
          SourceShardCodec.assemble(
            moduleId,
            shards,
            coreAnalysisRevision,
            compilerVersion = KotlinCompilerVersion.VERSION,
          ),
        )
      }
    }
  }
}
