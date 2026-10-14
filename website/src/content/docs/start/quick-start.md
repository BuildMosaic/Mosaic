---
title: 'Quick Start'
description: 'Create a Tile, bind request input, and compose a response in a runnable Kotlin program.'
---

Build a small response from request input, then compose it with another Tile. This tutorial uses Kotlin/JVM, JDK 21, and Gradle. You do not need an HTTP server. If you already have a Kotlin project, add the [core dependency](/start/installation/) and start at the program below.

## Create the project

Add `settings.gradle.kts` with `rootProject.name = "mosaic-hello"`, then create this build file. The Kotlin version matches Mosaic's own build toolchain.

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

The **Canvas** binds input under a typed key. `instance` registers the existing request value without transferring ownership to Canvas; use `single { ... }` when Canvas constructs and owns a value. `withLayer` adds request values without changing the application Canvas. `withMosaic { ... }` creates a **Mosaic** with its own Tile cache, owned by the calling coroutine. Leaving the block cancels unfinished work and waits for cleanup. `compose(GreetingTile)` executes the Tile and suspends until its value is available. The Tile reads the input using `source`.

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

## Put it behind an endpoint

At the request boundary, add input to your application Canvas, use `withMosaic` for one request Mosaic, and compose the response Tile from your suspending handler. Routing and HTTP errors remain in your framework. [Run the Spring Boot, Ktor, or Micronaut order example](/guides/frameworks/) to see a deeper graph in an application.

Request layers that only bind values need no explicit close. If a Canvas creates resources, give it an explicit [owner and close scope](/guides/resources/).

Continue with [Tiles and composition](/concepts/tiles/), or [test response logic](/guides/testing/) by replacing dependency Tiles.
