plugins {
  kotlin("jvm")
  kotlin("plugin.serialization") version "2.4.21"
  application
}

val mosaicVersion: String by rootProject.extra

dependencies {
  implementation(project(":tile-library"))
  implementation("org.buildmosaic:mosaic-core:$mosaicVersion")
  implementation("io.ktor:ktor-server-core:2.3.12")
  implementation("io.ktor:ktor-server-netty:2.3.12")
  implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
  implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
  implementation("io.ktor:ktor-server-status-pages:2.3.12")
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation("io.ktor:ktor-server-tests:2.3.12")
  testImplementation("io.ktor:ktor-client-content-negotiation:2.3.12")
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

application {
  mainClass.set("org.buildmosaic.ktor.orders.KtorExampleApplicationKt")
}
