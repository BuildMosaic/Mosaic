# Mosaic BOM (Bill of Materials)

The Mosaic BOM aligns `mosaic-core`, `mosaic-test`, and optional
`mosaic-opentelemetry` to the BOM version.

The coordinates below install published 0.7.0. Runtime 1.0.0 is not yet published;
see the [release notes](../docs/releases/runtime-1.0.0.md) for the upcoming migration.
The BOM version is independent of Analysis and does not select its compiler integration.

## Usage

Import the BOM and omit versions on Mosaic runtime dependencies. Add only the
libraries your application needs; importing the BOM does not install them.

### Gradle (Kotlin DSL)

```kotlin
// In an existing Kotlin/JVM project's build.gradle.kts

dependencies {
    // Import the BOM
    implementation(platform("org.buildmosaic:mosaic-bom:0.7.0"))

    // Add Mosaic dependencies without version numbers
    implementation("org.buildmosaic:mosaic-core")
    testImplementation("org.buildmosaic:mosaic-test")
    // Optional tracing adapter
    implementation("org.buildmosaic:mosaic-opentelemetry")
}
```

### Gradle (Groovy DSL)

```groovy
// In an existing Kotlin/JVM project's build.gradle

dependencies {
    // Import the BOM
    implementation platform('org.buildmosaic:mosaic-bom:0.7.0')

    // Add Mosaic dependencies without version numbers
    implementation 'org.buildmosaic:mosaic-core'
    testImplementation 'org.buildmosaic:mosaic-test'
    // Optional tracing adapter
    implementation 'org.buildmosaic:mosaic-opentelemetry'
}
```

### Maven

```xml
<project>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.buildmosaic</groupId>
                <artifactId>mosaic-bom</artifactId>
                <version>0.7.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.buildmosaic</groupId>
            <artifactId>mosaic-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.buildmosaic</groupId>
            <artifactId>mosaic-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

## Scope

The BOM aligns runtime libraries only. It does not include the optional
[analysis plugins](../mosaic-gradle-plugin/README.md), whose Kotlin version
requirements are separate from runtime compatibility. See
[core requirements](https://BuildMosaic.org/start/installation/)
for supported runtime toolchains.
