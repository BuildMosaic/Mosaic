---
title: 'Install Mosaic for Kotlin/JVM'
description: 'Add Mosaic from Maven Central, align runtime modules with the BOM, and choose compatible optional Analysis tooling.'
---

<!-- current-releases:start -->

Mosaic Runtime 1.1.0 supports Kotlin/JVM on Java 17 or later. Use Maven Central and a [supported Kotlin version](/reference/compatibility/).

Add core to your Gradle Kotlin DSL build:

```kotlin title="build.gradle.kts"
repositories {
  mavenCentral()
}

dependencies {
  implementation("org.buildmosaic:mosaic-core:1.1.0")
}
```

<!-- current-releases:end -->

Core exposes the coroutine APIs it uses. Runtime installation needs no compiler plugin, KSP processor, or Tile registration.

## Align optional modules

Use the BOM when adding testing or tracing:

<!-- current-releases:start -->

```kotlin title="build.gradle.kts"
dependencies {
  implementation(platform("org.buildmosaic:mosaic-bom:1.1.0"))
  implementation("org.buildmosaic:mosaic-core")
  // Optional execution tracing:
  implementation("org.buildmosaic:mosaic-opentelemetry")

  testImplementation(platform("org.buildmosaic:mosaic-bom:1.1.0"))
  testImplementation("org.buildmosaic:mosaic-test")
  testImplementation(kotlin("test"))
}
```

<!-- current-releases:end -->

The BOM aligns core, test, and OpenTelemetry modules. It does not add those libraries, install tracing, or align analysis tooling. The tracing adapter exposes OpenTelemetry API; your application provides its SDK or Java agent.

Continue with the [Quick Start](/start/quick-start/). Install optional build analysis separately using the [architecture guide](/guides/analysis/).
