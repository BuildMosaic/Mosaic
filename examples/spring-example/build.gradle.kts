plugins {
  kotlin("jvm")
  id("org.jetbrains.kotlin.plugin.spring") version "2.4.20"
  application
}

val mosaicVersion: String by rootProject.extra

dependencies {
  implementation(project(":tile-library"))
  implementation("org.buildmosaic:mosaic-core:$mosaicVersion")
  implementation("org.springframework.boot:spring-boot-starter-web:3.2.5")
  implementation(kotlin("reflect"))
  implementation(libs.kotlinx.coroutines.core)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${libs.versions.coroutines.get()}")
  testImplementation(kotlin("test"))
  testImplementation("org.springframework.boot:spring-boot-starter-test:3.2.5")
}

kotlin {
  jvmToolchain(21)
}

tasks.withType<Test> {
  useJUnitPlatform()
}

application {
  mainClass.set("org.buildmosaic.spring.orders.SpringExampleApplicationKt")
}
