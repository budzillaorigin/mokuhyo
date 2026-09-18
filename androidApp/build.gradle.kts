plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val nativeEnabled = providers.gradleProperty("tsumugi.native").orNull?.toBoolean() ?: true

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

    // Content packs are stored uncompressed in the APK: the first-launch copy is a plain read (no inflate) and
    // the APK/Play delivery compresses them anyway (F-16, D-052).
    androidResources {
        noCompress += "sqlite"
    }

    buildTypes {
        release {
            // Not minified: the APK is sideloaded (docs/RELEASE.md §7) and most of its size is native code and
            // content packs, which R8 wouldn't shrink. proguard-rules.pro keeps the JNI bridges if this is ever turned on.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // On-device LLM + STT: llama.cpp and whisper.cpp built from pinned sources (src/main/cpp/CMakeLists.txt).
    // On by default; `-Ptsumugi.native=false` skips the native build for quick UI-only iterations. The bridges
    // then report "native inference unavailable" rather than crashing.
    if (nativeEnabled) {
        ndkVersion = "29.0.14206865"
        defaultConfig {
            ndk { abiFilters += "arm64-v8a" }
            externalNativeBuild {
                cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static") }
            }
        }
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
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
    implementation(libs.androidx.work.runtime)
    implementation(libs.mlkit.text.recognition.japanese)
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

    /** content/models/manifest.json: the on-device model catalog, bundled as packs/models-manifest.json. */
    @get:InputFile
    abstract val modelsManifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("packs")
        out.deleteRecursively()
        out.mkdirs()
        packs.files.filter { it.isFile }.forEach { it.copyTo(out.resolve(it.name)) }
        modelsManifest.get().asFile.copyTo(out.resolve("models-manifest.json"), overwrite = true)
        licenses.get().asFile.copyTo(outputDir.get().asFile.resolve("LICENSES.md"), overwrite = true)
    }
}

val bundlePacks = tasks.register<BundlePacks>("bundlePacks") {
    packs.from(rootProject.fileTree("content/packs") { include("*.sqlite", "manifest.json") })
    licenses.set(rootProject.layout.projectDirectory.file("docs/LICENSES.md"))
    modelsManifest.set(rootProject.layout.projectDirectory.file("content/models/manifest.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundlePacks, BundlePacks::outputDir)
    }
}
