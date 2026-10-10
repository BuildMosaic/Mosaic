group = "org.buildmosaic"
val releaseTrain = if (project.name in setOf("mosaic-analysis-core", "mosaic-compiler-plugin", "mosaic-gradle-plugin")) "analysis" else "runtime"
version = providers.gradleProperty("mosaic.$releaseTrain.version").getOrElse(project.property("mosaic.version") as String)

repositories {
  mavenCentral()
  mavenLocal()
}
