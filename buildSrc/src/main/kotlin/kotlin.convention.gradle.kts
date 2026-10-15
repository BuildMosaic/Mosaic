import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  kotlin("jvm")
}

val isRuntimeModule = project.name in setOf("mosaic-core", "mosaic-test", "mosaic-opentelemetry")
val libraryKotlinVersion = if (isRuntimeModule) KotlinVersion.KOTLIN_2_2 else KotlinVersion.KOTLIN_2_4
val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
  if (isRuntimeModule) {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
    // Language/API settings do not select the stdlib or mosaic-test's published kotlin-test.
    coreLibrariesVersion = libs.findVersion("runtime-kotlin").get().requiredVersion
  }
  jvmToolchain(21)
  compilerOptions {
    // Keep the established Kotlin module name and internal JVM member suffixes.
    moduleName.set(project.name)
    jvmTarget.set(JvmTarget.JVM_17)
    languageVersion.set(libraryKotlinVersion)
    apiVersion.set(libraryKotlinVersion)
  }
}

java {
  targetCompatibility = JavaVersion.VERSION_17
  sourceCompatibility = JavaVersion.VERSION_17
}

