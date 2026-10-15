---
title: 'Quick Start'
description: 'Create a Tile, bind request input, and compose a response in a runnable Kotlin program.'
---

Build a small response from request input, then compose it with another Tile. This runnable tutorial uses published Mosaic 0.7.0, Kotlin/JVM, JDK 21, and Gradle. You do not need an HTTP server. If you already have a Kotlin project, add the [core dependency](/start/installation/) and start at the program below.

## Create the project

Add `settings.gradle.kts` with `rootProject.name = "mosaic-hello"`, then create this build file. This Kotlin version supports the published dependency below.

```kotlin title="build.gradle.kts"
plugins {
  kotlin("jvm") version "2.4.20"
  application
}

repositories { mavenCentral() }

dependencies {
  implementation("org.buildmosaic:mosaic-core:0.7.0")
}

kotlin { jvmToolchain(21) }
application { mainClass.set("MainKt") }
```

Use Gradle 8.14.4 or the wrapper in the Mosaic repository with JDK 21. The [compatibility page](/reference/compatibility/) distinguishes build and runtime requirements.

## Bind input and compose

Create `src/main/kotlin/Main.kt`:

```kotlin title="Main.kt"
import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import org.buildmosaic.core.injection.CanvasKey
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic

val UserIdKey = CanvasKey(String::class, "userId")
val GreetingTile by singleTile { "Hello, ${source(UserIdKey)}!" }

fun main() = runBlocking {
  val applicationCanvas = canvas {}
  val greeting = applicationCanvas.withLayer {
    instance(UserIdKey, "user-123")
  }.withMosaic { compose(GreetingTile) }
  println(greeting) // Hello, user-123!
}
```

Run `./gradlew run` if your project has a wrapper, or `gradle run`. The program prints `Hello, user-123!`.

The **Canvas** binds input under a typed key. `instance` registers the existing request value without transferring ownership to Canvas. `withLayer` adds request values without changing the application Canvas. `withMosaic { ... }` creates a **Mosaic** with its own Tile cache, owned by the calling coroutine. Leaving the block cancels unfinished work and waits for cleanup. `compose(GreetingTile)` executes the Tile and suspends until its value is available. The Tile reads the input using `source`.

## Compose the response from Tiles

Keep the imports and greeting Tile, add a response model and two declarations, and replace `main` with this version:

```kotlin title="Main.kt (composition)"
data class WelcomeResponse(val greeting: String, val help: String)

val HelpTile by singleTile { "Your account is ready." }
val WelcomeTile by singleTile {
  val greeting = composeAsync(GreetingTile)
  val help = composeAsync(HelpTile)
  WelcomeResponse(greeting.await(), help.await())
}

fun main() = runBlocking {
  val applicationCanvas = canvas {}
  val response = applicationCanvas.withLayer {
    instance(UserIdKey, "user-123")
  }.withMosaic { compose(WelcomeTile) }
  println(response)
}
```

The result is `WelcomeResponse(greeting=Hello, user-123!, help=Your account is ready.)`. Both branches start before you await their values. Neither needs to know how the other works. If two branches ask for `GreetingTile`, they share its work within this Mosaic.

## Preview Runtime 1.0.0

Runtime 1.0.0 is not published yet. With source-built 1.0.0 artifacts available to your build, the same complete programs above run unchanged with `implementation("org.buildmosaic:mosaic-core:1.0.0")`. Keep the 0.7.0 dependency for Maven Central installation until publication is confirmed on the [releases page](/reference/releases/).

The 1.0 Canvas vocabulary uses `provide` for eager owned construction, `instance` for borrowed values, `source` for required lookup, and `sourceOrNull` for optional lookup. For example, replace the first program's Canvas setup with this version; it still prints `Hello, user-123!`:

```kotlin
class GreetingService {
  fun greet(name: String) = "Hello, $name!"
}

val GreetingTile by singleTile {
  source<GreetingService>().greet(source(UserIdKey))
}

fun main() = runBlocking {
  val applicationCanvas = canvas { provide { GreetingService() } }
  try {
    val greeting = applicationCanvas.withLayer {
      instance(UserIdKey, "user-123")
    }.withMosaic { compose(GreetingTile) }
    println(greeting)
  } finally {
    applicationCanvas.close()
  }
}
```

Keep the original imports and `UserIdKey`, and replace the original `GreetingTile` and `main`. See [migration from 0.7.0](/reference/releases/#scoped-execution-migration) for removed APIs and test changes.

## Put it behind an endpoint

At the request boundary, add input to your application Canvas, use `withMosaic` for one request Mosaic, and compose the response Tile from your suspending handler. Routing and HTTP errors remain in your framework. [Run the Spring Boot, Ktor, or Micronaut order example](/guides/frameworks/) to see a deeper graph in an application.

Request layers that only bind values need no explicit close. If a Canvas creates resources, give it an explicit [owner and close scope](/guides/resources/).

Continue with [Tiles and composition](/concepts/tiles/), or [test response logic](/guides/testing/) by replacing dependency Tiles.
