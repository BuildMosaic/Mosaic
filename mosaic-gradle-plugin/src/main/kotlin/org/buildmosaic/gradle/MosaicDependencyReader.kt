package org.buildmosaic.gradle

import org.buildmosaic.analysis.ModuleContract
import org.buildmosaic.analysis.SummaryCodec
import org.buildmosaic.analysis.SummaryMetadata
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import java.io.IOException

internal object MosaicDependencyReader {
  fun read(dependencySummaries: ConfigurableFileCollection): List<ModuleContract> =
    metadata(dependencySummaries).map { it.module }

  fun metadata(dependencySummaries: ConfigurableFileCollection): List<SummaryMetadata> {
    val directories = dependencySummaries.files.filter { it.isDirectory }
    if (directories.isNotEmpty()) {
      throw GradleException("Mosaic verification requires dependency JARs, not class directories")
    }
    return dependencySummaries.files.map(::read).sortedBy { it.moduleId }
  }

  private fun read(summaryFile: java.io.File): SummaryMetadata =
    try {
      val summary = SummaryCodec.decode(summaryFile.readBytes())
      if (!summary.complete) throw GradleException("summary is partial")
      summary
    } catch (failure: RuntimeException) {
      invalidMetadata(summaryFile, failure)
    } catch (failure: IOException) {
      invalidMetadata(summaryFile, failure)
    }

  private fun invalidMetadata(
    jar: java.io.File,
    failure: Exception,
  ): Nothing {
    throw GradleException("${jar.name}: invalid Mosaic summary: ${failure.message}", failure)
  }
}
