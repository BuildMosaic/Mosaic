import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

description = "A testing framework for tile isolation in Mosaic"

plugins {
  id("kotlin.convention")
  id("quality.convention")
  id("testing.convention")
  id("library.convention")
}

// Main compilation uses friend access to subclass the internal engine, keeping Tile substitution
// out of production. Kotlin/Gradle compilation is authoritative; current IntelliJ versions may
// show false internal-visibility errors for this cross-project friend access.
val coreFriend =
  configurations.create("coreFriend") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
  }

// The compiler must load core from the same artifact it recognizes as a friend.
configurations.named("compileClasspath") {
  attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
}

tasks.named<KotlinJvmCompile>("compileKotlin") {
  friendPaths.from(coreFriend)
}

tasks.withType<Test> {
  jvmArgs =
    listOf(
      "-XX:+EnableDynamicAgentLoading",
      "-Djdk.instrument.traceUsage=false",
    )
}

dependencies {
  // Core Mosaic dependency
  api(project(":mosaic-core"))
  add(coreFriend.name, project(":mosaic-core"))

  // Coroutines dependency for main source set
  api(libs.kotlinx.coroutines.core)

  // Testing dependencies - needed for main source set since this is a testing framework
  implementation(kotlin("test"))
  api(libs.kotlinx.coroutines.test)
}
