plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.tsumugi.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.tsumugi.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * Bundles content packs built by tools/packs (every .sqlite in content/packs plus manifest.json, git-ignored) into the
 * APK under assets/packs/. When no packs have been built the app still builds and shows an honest
 * "dictionary not installed" state (CLAUDE.md rule 9).
 */
abstract class BundlePacks : DefaultTask() {
    @get:InputFiles
    abstract val packs: ConfigurableFileCollection

    /** docs/LICENSES.md, rendered by the in-app Licenses screen. */
    @get:InputFile
    abstract val licenses: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("packs")
        out.deleteRecursively()
        out.mkdirs()
        packs.files.filter { it.isFile }.forEach { it.copyTo(out.resolve(it.name)) }
        licenses.get().asFile.copyTo(outputDir.get().asFile.resolve("LICENSES.md"), overwrite = true)
    }
}

val bundlePacks = tasks.register<BundlePacks>("bundlePacks") {
    packs.from(rootProject.fileTree("content/packs") { include("*.sqlite", "manifest.json") })
    licenses.set(rootProject.layout.projectDirectory.file("docs/LICENSES.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundlePacks, BundlePacks::outputDir)
    }
}
