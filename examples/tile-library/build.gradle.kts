plugins {
  kotlin("jvm")
  kotlin("plugin.serialization") version "2.4.21"
}

val mosaicVersion: String by rootProject.extra

dependencies {
  // KotlinX Serialization
  implementation(libs.kotlinx.serialization.json)
  implementation("org.buildmosaic:mosaic-core:$mosaicVersion")

  // Coroutines
  implementation(libs.kotlinx.coroutines.core)

  // Test dependencies
  testImplementation(kotlin("test"))
  testImplementation("org.buildmosaic:mosaic-test:$mosaicVersion")
  testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
  jvmToolchain(21)
}

tasks.withType<Test> {
  useJUnitPlatform()
}
