---
title: 'Installation'
description: 'Add Mosaic to a Kotlin/JVM application and align optional runtime modules.'
---

The dependency declarations below install the published **0.7.0** release in a Kotlin/JVM project using Maven Central. Check its [released toolchain requirements](/reference/compatibility/#released-070); the other guides preview 1.0 where its API differs.

Add core to your Gradle Kotlin DSL build:

```kotlin title="build.gradle.kts"
repositories {
  mavenCentral()
}

dependencies {
  implementation("org.buildmosaic:mosaic-core:0.7.0")
}
```

Core exposes the coroutine APIs it uses. Runtime installation needs no compiler plugin, KSP processor, or Tile registration.

## Align optional modules

Use the BOM when adding testing or tracing:

```kotlin title="build.gradle.kts"
dependencies {
  implementation(platform("org.buildmosaic:mosaic-bom:0.7.0"))
  implementation("org.buildmosaic:mosaic-core")
  // Optional execution tracing:
  implementation("org.buildmosaic:mosaic-opentelemetry")

  testImplementation(platform("org.buildmosaic:mosaic-bom:0.7.0"))
  testImplementation("org.buildmosaic:mosaic-test")
  testImplementation(kotlin("test"))
}
```

The BOM aligns core, test, and OpenTelemetry modules. It does not add those libraries, install tracing, or align analysis tooling. The tracing adapter exposes OpenTelemetry API; your application provides its SDK or Java agent.

Continue with the [Quick Start](/start/quick-start/). Install optional build analysis separately using the [architecture guide](/guides/analysis/).
