package org.buildmosaic.analysis

import java.io.File
import java.util.jar.JarFile

/** Core owns the requirement; Analysis independently owns admission. Release versions are provenance. */
object CoreAnalysisRevision {
  const val MANIFEST_ATTRIBUTE = "Mosaic-Core-Analysis-Revision"
  private const val CORE_CLASS = "org/buildmosaic/core/injection/Canvas.class"
  private val understoodRevisions = setOf(1)

  fun requireUnderstood(revisions: Collection<Int>) {
    require(revisions.isNotEmpty()) { "Missing Mosaic Core semantic requirement. Regenerate the Mosaic summary." }
    revisions.forEach { revision ->
      require(revision in understoodRevisions) {
        "Unsupported Mosaic Core analysis revision $revision; Analysis understands $understoodRevisions. " +
          "Select a supported mosaic-core artifact or update Mosaic Analysis."
      }
    }
  }

  /** Targeted lookup on the actual classpath, never the tooling classloader or artifact filename. */
  fun selected(
    classpath: Collection<File>,
    required: Boolean = true,
  ): Int? {
    val cores =
      classpath.filter { artifact ->
        if (artifact.isDirectory) {
          File(artifact, CORE_CLASS).isFile
        } else if (artifact.isFile && artifact.extension == "jar") {
          JarFile(artifact).use { it.getJarEntry(CORE_CLASS) != null }
        } else {
          false
        }
      }
    require(cores.size <= 1) { "Ambiguous selected Mosaic Core artifacts: ${cores.joinToString { it.name }}" }
    if (cores.isEmpty()) {
      require(!required) { "No selected mosaic-core JAR. Add mosaic-core to the analyzed compilation classpath." }
      return null
    }
    val core = cores.single()
    require(core.isFile) { "Selected Mosaic Core must be a JAR with $MANIFEST_ATTRIBUTE: ${core.name}" }
    val value = JarFile(core).use { it.manifest?.mainAttributes?.getValue(MANIFEST_ATTRIBUTE) }
    require(value != null && value.matches(Regex("[1-9][0-9]*"))) {
      "Selected Mosaic Core ${core.name} has missing or malformed $MANIFEST_ATTRIBUTE: ${value ?: "<missing>"}. " +
        "Select a mosaic-core artifact declaring its analysis revision."
    }
    val revision = requireNotNull(value.toIntOrNull()) { "Malformed $MANIFEST_ATTRIBUTE: $value" }
    requireUnderstood(listOf(revision))
    return revision
  }

  fun selectedContext(
    compile: Collection<File>,
    runtime: Collection<File>,
  ): Int {
    val revision = requireNotNull(selected(compile))
    val runtimeRevision = selected(runtime, required = false)
    require(runtimeRevision == null || runtimeRevision == revision) {
      "Conflicting selected Mosaic Core analysis revisions: compile=$revision, runtime=$runtimeRevision. " +
        "Select the same Core semantics on both classpaths."
    }
    return revision
  }
}
