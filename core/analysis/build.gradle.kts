import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure JVM: image statistics, parameter estimation and the CPU reference of the correction.
// No Android dependencies, so everything here is unit-testable without an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_11 }
}

dependencies {
    testImplementation(libs.junit)
}

// ReferenceSetTest reads the maintainer's local reference pairs (see tools/reference/README.md) and is
// skipped when they are absent, e.g. on CI or a contributor's clone.
val referenceDir = rootProject.layout.projectDirectory.dir("trainingset/reference")
tasks.test {
    systemProperty("divelens.reference", referenceDir.asFile.absolutePath)
    inputs.files(fileTree(referenceDir)).withPropertyName("referenceSet").optional()
}
