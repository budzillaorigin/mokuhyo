plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val nativeEnabled = providers.gradleProperty("tsumugi.native").orNull?.toBoolean() ?: true

/**
 * Release signing (docs/RELEASE.md §7): a Gradle property (put it in ~/.gradle/gradle.properties, never in this repo)
 * or an environment variable, e.g. `tsumugi.release.storeFile` / `TSUMUGI_RELEASE_STORE_FILE`. With no keystore
 * configured the release APK/AAB is built unsigned.
 */
fun releaseSetting(name: String, env: String): String? =
    providers.gradleProperty("tsumugi.release.$name").orElse(providers.environmentVariable(env)).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSetting("storeFile", "TSUMUGI_RELEASE_STORE_FILE")

android {
    namespace = "app.tsumugi.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.tsumugi.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Play needs a higher versionCode for every upload: `-Ptsumugi.versionCode=2` (or raise the default here).
        versionCode = providers.gradleProperty("tsumugi.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("tsumugi.versionName").orNull ?: "0.0.1"
    }

    buildFeatures {
        compose = true
    }

    // Content packs are stored uncompressed in the APK: the first-launch copy is a plain read (no inflate) and
    // the APK/Play delivery compresses them anyway (F-16, D-052).
    androidResources {
        noCompress += "sqlite"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseSetting("storePassword", "TSUMUGI_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSetting("keyAlias", "TSUMUGI_RELEASE_KEY_ALIAS")
                keyPassword = releaseSetting("keyPassword", "TSUMUGI_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Not minified (D-316): most of the size is native code and content packs, which R8 wouldn't shrink, and
            // keep rules for kotlinx-serialization, Ktor, SQLDelight and ML Kit aren't verified on a device.
            // proguard-rules.pro already keeps the JNI bridges for when this is turned on.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned when no keystore is configured (see releaseSetting above).
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // App Bundle (`bundleRelease`, for Play). Both UI languages stay in the base module: the per-app language
    // setting (Android 13+) can switch to a language the device doesn't use, whose split wouldn't be installed.
    bundle {
        language { enableSplit = false }
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
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.mlkit.text.recognition.japanese)
    // Okio types appear in shared APIs the app calls (audio pack paths and installs, D-095); already in the APK via shared.
    implementation(libs.okio)
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
    // Audio: only the small pitch and minimal-pairs sets ship inside the APK (D-097); the rest are downloads.
    packs.from(rootProject.fileTree("content/packs") {
        include("*.sqlite", "manifest.json", "audio-manifest.json", "audio-pitch.zip", "audio-minimal-pairs.zip")
    })
    licenses.set(rootProject.layout.projectDirectory.file("docs/LICENSES.md"))
    modelsManifest.set(rootProject.layout.projectDirectory.file("content/models/manifest.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundlePacks, BundlePacks::outputDir)
    }
}
