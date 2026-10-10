import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.plugins.signing.Sign

plugins {
  id("com.vanniktech.maven.publish")
  signing
}

val installTestRepository = providers.gradleProperty("mosaic.installTestRepository").orNull
val signingInMemoryKey = providers.gradleProperty("signingInMemoryKey").orNull
val signingInMemoryKeyPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull

mavenPublishing {
  if (installTestRepository == null) {
    publishToMavenCentral()
    signAllPublications()
  }

  coordinates(
    groupId = project.group.toString(),
    artifactId = project.name,
    version = project.version.toString(),
  )

  pom {
    name.set(project.name)
    description.set(project.description)
    url.set("https://github.com/BuildMosaic/Mosaic/tree/main/${project.name}")

    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
      }
    }

    developers {
      developer {
        name.set("Nicholas Abbott")
        email.set("nick@buildmosaic.org")
        url.set("https://github.com/Nick-Abbott")
      }
    }

    scm {
      url.set("https://github.com/BuildMosaic/Mosaic/")
      connection.set("scm:git:https://github.com/BuildMosaic/Mosaic.git")
      developerConnection.set("scm:git:ssh://git@github.com/BuildMosaic/Mosaic.git")
    }
  }
}

signing {
  isRequired = installTestRepository == null
  if (signingInMemoryKey != null) {
    useInMemoryPgpKeys(signingInMemoryKey, signingInMemoryKeyPassword)
  } else {
    useGpgCmd()
  }
  if (installTestRepository == null) sign(publishing.publications)
}

tasks.withType<Sign>().configureEach {
  if (installTestRepository != null) enabled = false
}

publishing {
  repositories {
    installTestRepository?.let { installRepository ->
      maven {
        name = "installTest"
        url = uri(installRepository)
      }
    }
  }
}

// Analysis is one coordinated release. Reject partial publishing even through low-level tasks.
if (project.name in setOf("mosaic-compiler-plugin", "mosaic-gradle-plugin")) {
  val requestedTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
  val completeMavenRelease = requestedTasks.any {
    it in setOf("release", "releaseToMavenCentral", "releaseAnalysis", "releaseAnalysisToMavenCentral")
  }
  val completeInstallation = "publishAnalysisToInstallTestRepository" in requestedTasks
  tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(rootProject.tasks.named("prepareAnalysisPublication"))
    val coordinated = if (installTestRepository != null) completeInstallation else completeMavenRelease
    doFirst {
      check(coordinated) {
        "Publish the complete Mosaic Analysis set with releaseAnalysisToMavenCentral or publishAnalysisToInstallTestRepository"
      }
    }
  }
  tasks.withType<PublishToMavenLocal>().configureEach {
    doFirst { error("Use publishAnalysisToInstallTestRepository to install the complete Mosaic Analysis set") }
  }
}
