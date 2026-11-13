package org.buildmosaic.gradle

import org.buildmosaic.analysis.CoreAnalysisRevision
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Separates packaged artifact changes from the semantic input to Kotlin compilation. */
@CacheableTask
abstract class ResolveMosaicCoreRevisionTask : DefaultTask() {
  @get:Classpath
  abstract val coreArtifacts: ConfigurableFileCollection

  @get:OutputFile
  abstract val revisionFile: RegularFileProperty

  @TaskAction
  fun resolve() {
    val revision = requireNotNull(CoreAnalysisRevision.selected(coreArtifacts.files))
    revisionFile.get().asFile.apply {
      parentFile.mkdirs()
      writeText(revision.toString())
    }
  }
}
