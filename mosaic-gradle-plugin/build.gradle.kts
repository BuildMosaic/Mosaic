import org.gradle.api.tasks.WriteProperties
import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata

description = "Mosaic contract analysis for Kotlin/JVM Gradle builds"

plugins {
  id("kotlin.convention")
  id("quality.convention")
  id("java-gradle-plugin")
  id("publish.convention")
  alias(libs.plugins.plugin.publish)
}

val pluginVersionMetadata =
  tasks.register<WriteProperties>("generateMosaicAnalysisVersion") {
    destinationFile =
      layout.buildDirectory.file("generated/mosaic-analysis/org/buildmosaic/gradle/version.properties").get().asFile
    property("version", project.version.toString())
  }
tasks.processResources {
  dependsOn(pluginVersionMetadata)
  from(layout.buildDirectory.dir("generated/mosaic-analysis"))
}

val testKitKotlinPlugin =
  configurations.create("mosaicTestKitKotlinPlugin") {
    isCanBeConsumed = false
    isCanBeResolved = true
  }

dependencies {
  compileOnly(project(":mosaic-analysis-core"))
  testImplementation(project(":mosaic-analysis-core"))
  compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  add(testKitKotlinPlugin.name, "org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  testImplementation(project(":mosaic-core"))
  testImplementation(project(":mosaic-compiler-plugin"))
  testImplementation(gradleTestKit())
  testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
  testImplementation(kotlin("test"))
}

val bundledAnalysisRuntime =
  configurations.create("bundledAnalysisRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
  }
dependencies { add(bundledAnalysisRuntime.name, project(":mosaic-analysis-core")) }

tasks.jar {
  dependsOn(bundledAnalysisRuntime)
  duplicatesStrategy = DuplicatesStrategy.FAIL
  exclude("META-INF/versions/**/module-info.class")
  from({
    bundledAnalysisRuntime.filter { it.extension == "jar" }.map { zipTree(it) }
  })
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  systemProperty(
    "mosaic.test.javaInstallations",
    providers.gradleProperty("org.gradle.java.installations.paths").getOrElse(""),
  )
  providers.gradleProperty(
    "mosaic.test.kotlinVersions",
  ).orNull?.let { systemProperty("mosaic.test.kotlinVersions", it) }
  providers.gradleProperty("mosaic.test.runtimeRepository").orNull?.let {
    systemProperty("mosaic.test.runtimeRepository", it)
  }
}

tasks.test {
  useJUnitPlatform { excludeTags("release-compatibility") }
}

tasks.register<Test>("releaseCompatibilityTest") {
  group = "compatibility"
  description = "Real Maven installation matrix and cross-compiler cache regressions for release certification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags("release-compatibility") }
  dependsOn(":mosaic-compiler-plugin:jar", ":mosaic-core:jar")
  outputs.upToDateWhen { false }
}

tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
  pluginClasspath.from(tasks.jar)
  pluginClasspath.from(testKitKotlinPlugin)
}

gradlePlugin {
  plugins {
    create("mosaicAnalysis") {
      id = "org.buildmosaic.analysis"
      displayName = "Mosaic Analysis"
      description = "Kotlin/JVM Mosaic contract extraction and verification"
      tags.set(listOf("kotlin", "analysis"))
      implementationClass = "org.buildmosaic.gradle.MosaicAnalysisPlugin"
    }
  }
}

gradlePlugin {
  website = "https://github.com/BuildMosaic/Mosaic"
  vcsUrl = "https://github.com/BuildMosaic/Mosaic.git"
}

tasks.test {
  dependsOn(":mosaic-compiler-plugin:jar", ":mosaic-core:jar")
}

tasks.named("publishPlugins") {
  dependsOn(rootProject.tasks.named("releaseAnalysisToMavenCentral"))
  mustRunAfter(rootProject.tasks.named("releaseAnalysisToMavenCentral"))
}
