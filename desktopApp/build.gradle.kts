import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.mp)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
    }
}

val appVersion = providers.gradleProperty("mokuhyo.version").getOrElse("0.1.0")
// macOS bundles refuse a 0.x version (CFBundleShortVersionString must start at 1): 0.y.z ships as 1.y.z there (D-008).
val macBundleVersion = appVersion.split(".").let { p -> if (p[0] == "0") (listOf("1") + p.drop(1)).joinToString(".") else appVersion }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.java)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.icu4j)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "app.mokuhyo.desktop.MainKt"
        jvmArgs += listOf("-Xss4m", "-Dfile.encoding=UTF-8", "-Dmokuhyo.version=$appVersion")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Mokuhyo"
            packageVersion = appVersion
            description = "DLPT- and OPI-style practice for 11 languages, fully offline"
            vendor = "Mokuhyo contributors"
            copyright = "© 2026 Mokuhyo contributors. Apache-2.0."
            licenseFile.set(rootProject.file("LICENSE"))
            // Bundled resources: native libs per OS/arch, Whisper small, voices, content packs (see tools/release/).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.sql", "java.net.http", "jdk.management", "jdk.unsupported", "java.desktop", "java.naming")
            macOS {
                bundleID = "app.mokuhyo.desktop"
                packageVersion = macBundleVersion
                dmgPackageVersion = macBundleVersion
                appCategory = "public.app-category.education"
                iconFile.set(project.file("icons/mokuhyo.icns"))
            }
            windows {
                menuGroup = "Mokuhyo"
                upgradeUuid = "5b8f1f0e-3c1d-4d6c-9a77-6d2a8f0f6a11"
                perUserInstall = true
                shortcut = true
                iconFile.set(project.file("icons/mokuhyo.ico"))
            }
            linux {
                packageName = "mokuhyo"
                appCategory = "Education"
                shortcut = true
                iconFile.set(project.file("icons/mokuhyo.png"))
            }
        }
    }
}

// The packaged app's headless smoke test (BRIEF §11.1 gate_build) needs the app resources next to the dev run too.
tasks.withType<JavaExec>().configureEach {
    systemProperty("mokuhyo.repo.dir", rootProject.projectDir.absolutePath)
}

// Ship the model manifest and the licenses file inside the jar (read by the model picker and the Licenses screen).
val bundledDocs by tasks.registering(Copy::class) {
    from(rootProject.file("content/models/manifest.json")) { into("models") }
    from(rootProject.file("docs/LICENSES.md")) { into("docs") }
    from(rootProject.file("docs/MODELS.md")) { into("docs") }
    into(layout.buildDirectory.dir("generated/bundled"))
}
sourceSets.main { resources.srcDir(bundledDocs) }
