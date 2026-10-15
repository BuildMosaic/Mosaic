description = "The core tile composition library for Mosaic"

plugins {
  id("kotlin.convention")
  id("quality.convention")
  id("testing.convention")
  id("library.convention")
}

tasks.jar {
  manifest.attributes("Mosaic-Core-Analysis-Revision" to "1")
}

dependencies {
  api(libs.kotlinx.coroutines.core)
  testImplementation(libs.kotlinx.coroutines.test)
}
