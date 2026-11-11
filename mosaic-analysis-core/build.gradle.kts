description = "Compiler-independent semantic analysis for Mosaic contracts"

plugins {
  id("kotlin.convention")
  id("quality.convention")
  id("testing.convention")
  `java-library`
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
}

tasks.test {
  // RuleReferenceTest checks the authored reference against the rule registry.
  inputs.file(rootProject.layout.projectDirectory.file("website/src/content/docs/reference/analysis-configuration.md"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Kept with the bundled Analysis code in both compiler and Gradle artifacts.
tasks.processResources {
  val analysisArtifactVersion = project.version.toString()
  inputs.property("analysisVersion", analysisArtifactVersion)
  filesMatching("org/buildmosaic/analysis/version.properties") {
    expand("analysisVersion" to analysisArtifactVersion)
  }
}
