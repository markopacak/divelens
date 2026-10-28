import java.util.Properties
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from a gitignored keystore.properties in the project root (see README).
// Without it, release builds are unsigned and debug builds are unaffected.
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.markopacak.divelens"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.markopacak.divelens"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystoreProperties != null) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.telephoto.zoomable)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(project(":core:analysis"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
/**
 * Fails when the app ships a library whose Maven group no "Maven groups:" line in NOTICE covers
 * (add-help-and-legal D5). A pattern ending in ".*" covers every group below it.
 */
abstract class CheckNotice : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notice: RegularFileProperty

    @get:Input
    abstract val groups: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val patterns = notice.get().asFile.readLines().map { it.trim() }.filter { it.startsWith("Maven groups:") }
            .flatMap { line -> line.removePrefix("Maven groups:").split(",").map { it.trim() } }
        fun covered(group: String) = patterns.any { if (it.endsWith(".*")) group.startsWith(it.removeSuffix("*")) else group == it }
        val missing = groups.get().filterNot(::covered).sorted()
        if (missing.isNotEmpty()) {
            throw GradleException("NOTICE does not list these shipped libraries (add an entry with a \"Maven groups:\" line): ${missing.joinToString()}")
        }
        report.get().asFile.writeText(groups.get().sorted().joinToString("\n"))
    }
}

val checkNotice by tasks.registering(CheckNotice::class) {
    notice = rootProject.layout.projectDirectory.file("NOTICE")
    groups = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }.map { root ->
        val seen = mutableSetOf<ResolvedComponentResult>()
        val found = sortedSetOf<String>()
        fun walk(component: ResolvedComponentResult) {
            if (!seen.add(component)) return
            (component.id as? ModuleComponentIdentifier)?.let { found += it.group }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { walk(it.selected) }
        }
        walk(root)
        found
    }
    report = layout.buildDirectory.file("reports/notice/groups.txt")
}

tasks.named("preBuild") { dependsOn(checkNotice) }

/**
 * Copies the legal texts from the repository into a generated `res/raw` directory, so the app shows exactly the
 * published files (add-help-and-legal D4): R.raw.legal_license, legal_notice, legal_privacy and legal_terms.
 */
abstract class CopyLegalTexts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val texts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val raw = outputDir.get().dir("raw").asFile
        raw.deleteRecursively()
        raw.mkdirs()
        val names = mapOf("LICENSE" to "legal_license.txt", "NOTICE" to "legal_notice.txt", "PRIVACY.md" to "legal_privacy.md", "TERMS.md" to "legal_terms.md")
        texts.files.forEach { file -> file.copyTo(raw.resolve(names.getValue(file.name)), overwrite = true) }
    }
}

/**
 * Copies the watermark SVGs from docs/brand/ into generated assets (`brand/watermark_light.svg`, `watermark_dark.svg`),
 * so exports draw exactly the brand files (add-branding-and-watermark D4).
 */
abstract class CopyBrandMarks : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val marks: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val brand = outputDir.get().dir("brand").asFile
        brand.deleteRecursively()
        brand.mkdirs()
        marks.files.forEach { file -> file.copyTo(brand.resolve(file.name), overwrite = true) }
    }
}

androidComponents {
    onVariants { variant ->
        val copy = tasks.register<CopyLegalTexts>("copy${variant.name.replaceFirstChar { it.uppercase() }}LegalTexts") {
            texts.from(rootProject.files("LICENSE", "NOTICE", "docs/PRIVACY.md", "docs/TERMS.md"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(copy, CopyLegalTexts::outputDir)
        val marks = tasks.register<CopyBrandMarks>("copy${variant.name.replaceFirstChar { it.uppercase() }}BrandMarks") {
            marks.from(rootProject.files("docs/brand/watermark/watermark_light.svg", "docs/brand/watermark/watermark_dark.svg"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(marks, CopyBrandMarks::outputDir)
    }
}
