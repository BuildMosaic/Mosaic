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
