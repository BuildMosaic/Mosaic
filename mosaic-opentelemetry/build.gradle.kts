description = "OpenTelemetry tracing for Mosaic backend orchestration in Kotlin"

plugins {
  id("kotlin.convention")
  id("quality.convention")
  id("testing.convention")
  id("library.convention")
}

dependencies {
  api(project(":mosaic-core"))
  api(libs.opentelemetry.api)
  testImplementation(libs.opentelemetry.sdk)
  testImplementation(libs.opentelemetry.sdk.testing)
  testImplementation(libs.opentelemetry.kotlin)
}
