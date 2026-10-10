plugins {
  id("base.convention")
  id("dokka.convention")
  id("org.jetbrains.dokka-javadoc")
}

allprojects {
  apply(plugin = "base.convention")
}

tasks.register("releaseRuntime") {
  group = "publishing"
  description = "Publish Runtime modules and the Runtime-only BOM to Maven Central"
  dependsOn(
    ":mosaic-core:publishAndReleaseToMavenCentral",
    ":mosaic-test:publishAndReleaseToMavenCentral",
    ":mosaic-opentelemetry:publishAndReleaseToMavenCentral",
    ":mosaic-bom:publishAndReleaseToMavenCentral",
  )
}

tasks.register("releaseAnalysis") {
  group = "publishing"
  description = "Publish the complete Analysis set to Maven Central and the Gradle Plugin Portal"
  dependsOn(
    ":mosaic-compiler-plugin:publishAndReleaseToMavenCentral",
    ":mosaic-gradle-plugin:publishAndReleaseToMavenCentral",
    ":mosaic-gradle-plugin:publishPlugins",
  )
}

dependencies {
  dokka(project(":mosaic-core"))
  dokka(project(":mosaic-test"))
  dokka(project(":mosaic-opentelemetry"))
}

dokka {
  moduleName.set("Mosaic")
}

// Deliberately disconnected from build/check/test and ordinary validation workflows.
listOf(
  "certifyKotlin" to "all",
  "certifyKotlinRuntime" to "runtime",
  "certifyKotlinAnalysis" to "analysis",
).forEach { (taskName, scope) ->
  tasks.register<Exec>(taskName) {
    group = "compatibility"
    description = "On-demand $scope compatibility harness for an exact stable -Pcompat.kotlin version"
    commandLine(
      "python3",
      layout.projectDirectory.file("compatibility/certify.py").asFile.absolutePath,
      "--kotlin",
      providers.gradleProperty("compat.kotlin").getOrElse(""),
      "--scope",
      scope,
      "--java-installations",
      providers.gradleProperty("org.gradle.java.installations.paths").getOrElse(""),
    )
    if (providers.gradleProperty("compat.probe").orNull == "true") {
      args("--compatibility-probe")
    }
    providers.gradleProperty("compat.introspectorApi").orNull?.let { args("--introspector-api", it) }
    // The harness always produces fresh evidence, including failures.
    outputs.upToDateWhen { false }
  }
}

tasks.register<Exec>("certifyReleaseCompatibility") {
  group = "compatibility"
  description = "Certify every supported Kotlin compiler before publication (-Pcompat.scope=runtime|analysis|all)"
  commandLine(
    "python3",
    layout.projectDirectory.file("compatibility/certify.py").asFile.absolutePath,
    "--release-matrix",
    "--scope",
    providers.gradleProperty("compat.scope").getOrElse("all"),
    "--java-installations",
    providers.gradleProperty("org.gradle.java.installations.paths").getOrElse(""),
  )
  outputs.upToDateWhen { false }
}
