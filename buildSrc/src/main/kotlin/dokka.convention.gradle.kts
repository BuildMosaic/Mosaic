plugins {
  id("org.jetbrains.dokka")
}

// Dokka recognizes this filename as its native header logo (and favicon).
// Generate the alias from the canonical mark; there is only one editable master.
val prepareDokkaBrandAssets by tasks.registering(Sync::class) {
  from(rootProject.layout.projectDirectory.file("assets/mosaic-mark.svg"))
  into(layout.buildDirectory.dir("dokka-brand"))
  rename("mosaic-mark.svg", "logo-icon.svg")
}

dokka {
  pluginsConfiguration.html {
    customStyleSheets.from(rootProject.layout.projectDirectory.file("website/api/mosaic.css"))
    customAssets.from(
      prepareDokkaBrandAssets.map { it.destinationDir.resolve("logo-icon.svg") },
      rootProject.layout.projectDirectory.file("website/api/mosaic-api.js"),
    )
    separateInheritedMembers.set(true)
    footerMessage.set("<a href=\"https://BuildMosaic.org/\">BuildMosaic.org</a> · <a href=\"https://BuildMosaic.org/start/overview/\">User documentation</a>")
  }
}
