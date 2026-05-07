plugins {
    // To optionally create a shadow/fat jar that bundles up dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

// Configure your extension here
qupathExtension {
    name = "qupath-extension-channel-names-viewer"
    group = "io.github.michaelsnelson"
    version = "1.0.0"
    description = "A small always-visible legend window listing the currently selected fluorescence channels in QuPath."
    automaticModule = "io.github.michaelsnelson.extension.channelnamesviewer"
}

repositories {
    mavenLocal()
    mavenCentral()
}

val javafxVersion = "17.0.2"

dependencies {
    // Main dependencies for QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.openjfx:javafx-base:$javafxVersion")
    testImplementation("org.openjfx:javafx-graphics:$javafxVersion")
    testImplementation("org.openjfx:javafx-controls:$javafxVersion")
}

// For troubleshooting deprecation warnings
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    useJUnitPlatform()
    // Pure-Java unit tests for preferences sentinel handling and listener
    // accounting. JavaFX classes are on the classpath via testImplementation,
    // but tests do not initialize the JavaFX toolkit. If a future test
    // exercises JavaFX directly, add:
    //   "--add-modules", "javafx.base,javafx.graphics,javafx.controls",
    //   "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED"
    // and configure the openjfx Gradle plugin so the modules land on the
    // module path rather than the classpath.
}
