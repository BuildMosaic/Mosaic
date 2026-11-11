description = "Bill of Materials for the Mosaic Kotlin backend orchestration runtime"

plugins {
    `java-platform`
    id("publish.convention")
}

val mosaicVersion = version

// Configure the BOM
javaPlatform {
    allowDependencies()
}

dependencies {
    // Define constraints for all the dependencies that will be used in the BOM
    constraints {
        api("org.buildmosaic:mosaic-core:${mosaicVersion}")
        api("org.buildmosaic:mosaic-test:${mosaicVersion}")
        api("org.buildmosaic:mosaic-opentelemetry:${mosaicVersion}")
    }
}
