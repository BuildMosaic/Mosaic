import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

abstract class CompilerFixtureArguments : CommandLineArgumentProvider {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.ABSOLUTE)
  abstract val pluginJar: RegularFileProperty

  @get:Classpath
  abstract val fixtureClasspath: ConfigurableFileCollection

  override fun asArguments(): Iterable<String> =
    listOf(
      "-Dmosaic.plugin.jar=${pluginJar.get().asFile.absolutePath}",
      "-Dmosaic.fixture.classpath=${fixtureClasspath.asPath}",
    )
}

description = "Experimental Kotlin IR extraction for Mosaic"

plugins {
  id("kotlin.convention")
  id("quality.convention")
  id("library.convention")
}

// The host compiler may advance without changing the measured compiler-facing ABI builds.
val compilerProfiles =
  Properties().apply {
    rootProject.file("mosaic-analysis-core/src/main/resources/org/buildmosaic/analysis/compiler-profiles.properties")
      .inputStream().use(::load)
  }
val defaultCompilerApi = compilerProfiles.getProperty("default")

dependencies {
  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$defaultCompilerApi")
  compileOnly(project(":mosaic-analysis-core"))
  testImplementation(project(":mosaic-analysis-core"))
  testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
  testImplementation(project(":mosaic-core"))
  testImplementation(kotlin("test"))
}

val bundledCompilerRuntime =
  configurations.create("bundledCompilerRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
  }
dependencies { add(bundledCompilerRuntime.name, project(":mosaic-analysis-core")) }

fun Jar.bundleIntrospector(compilerApi: String) {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  dependsOn(bundledCompilerRuntime)
  duplicatesStrategy = DuplicatesStrategy.FAIL
  exclude("META-INF/versions/**/module-info.class")
  manifest.attributes("Implementation-Version" to project.version.toString())
  manifest.attributes("Mosaic-Compiler-API" to compilerApi)
  from({
    bundledCompilerRuntime.filter { it.extension == "jar" }.map { zipTree(it) }
  })
}

tasks.jar { bundleIntrospector(defaultCompilerApi) }

// Recompile the same compiler-facing sources against each demonstrated ABI boundary.
// The host compiler, contract kernel, and bundled runtime are built once.
val introspectorJars =
  tasks.register("introspectorJars") {
    group = "build"
    description = "Build the shared-source introspector jars for the measured compiler ABI boundaries"
    dependsOn(tasks.jar)
  }
compilerProfiles.stringPropertyNames().filter { it != "default" && it != defaultCompilerApi }.sorted().forEach {
    compilerApi ->
  val suffix = compilerApi.replace(".", "_")
  val profileSources = sourceSets.create("introspector$suffix")
  kotlin.sourceSets.named(profileSources.name) { kotlin.srcDir("src/main/kotlin") }
  dependencies {
    add(profileSources.compileOnlyConfigurationName, "org.jetbrains.kotlin:kotlin-compiler-embeddable:$compilerApi")
    add(profileSources.compileOnlyConfigurationName, project(":mosaic-analysis-core"))
  }
  val compile =
    tasks.named<KotlinCompile>(profileSources.getCompileTaskName("kotlin")) {
      compilerOptions {
        moduleName.set(project.name)
        jvmTarget.set(JvmTarget.JVM_17)
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
      }
    }
  val jar =
    tasks.register<Jar>("introspectorJar$suffix") {
      bundleIntrospector(compilerApi)
      archiveClassifier.set("kotlin-$compilerApi")
      from(compile.flatMap { it.destinationDirectory })
      from(sourceSets.main.get().output.resourcesDir)
      dependsOn(tasks.processResources)
    }
  afterEvaluate {
    publishing.publications.create<MavenPublication>("introspector$suffix") {
      artifactId = "mosaic-compiler-plugin-kotlin-$compilerApi"
      artifact(jar) { classifier = null }
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("dokkaJavadocJar"))
    }
  }
  introspectorJars.configure { dependsOn(jar) }
}
tasks.assemble { dependsOn(introspectorJars) }

tasks.test {
  useJUnitPlatform()
  dependsOn(tasks.jar, ":mosaic-core:jar")
  // The in-process compiler must load the packaged registrar, including its ABI identity.
  classpath = classpath.minus(sourceSets.main.get().output).plus(files(tasks.jar.flatMap { it.archiveFile }))
  val fixtureArguments = objects.newInstance<CompilerFixtureArguments>()
  fixtureArguments.pluginJar.set(tasks.jar.flatMap { it.archiveFile })
  fixtureArguments.fixtureClasspath.from(classpath.filter { !it.path.contains("mosaic-core/build/classes") })
  fixtureArguments.fixtureClasspath.from(
    project(":mosaic-core").tasks.named<Jar>("jar").flatMap { it.archiveFile },
  )
  jvmArgumentProviders.add(fixtureArguments)
}
