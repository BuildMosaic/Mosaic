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
    // The harness always produces fresh evidence, including failures.
    outputs.upToDateWhen { false }
  }
}

tasks.register<Exec>("certifyReleaseCompatibility") {
  group = "compatibility"
  description = "Certify a release inside its intended compatibility ranges (-Pcompat.scope=runtime|analysis, -Pcompat.release=VERSION)"
  commandLine(
    "python3",
    layout.projectDirectory.file("compatibility/certify.py").asFile.absolutePath,
    "--scope",
    providers.gradleProperty("compat.scope").getOrElse("all"),
    "--java-installations",
    providers.gradleProperty("org.gradle.java.installations.paths").getOrElse(""),
  )
  args("--release", providers.gradleProperty("compat.release").getOrElse(""))
  listOf("minKotlin", "maxKotlin", "minRuntime", "maxRuntime").forEach { bound ->
    providers.gradleProperty("compat.$bound").orNull?.let { args("--$bound", it) }
  }
  outputs.upToDateWhen { false }
}
