plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xexpect-actual-classes")
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
    }

    // Desktop first (BRIEF §3). commonMain stays free of JVM APIs so android/ios targets could return later.
    jvm()
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            api(libs.okio)
            implementation(libs.ktor.client.core)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.icu4j)
            implementation(libs.pdfbox)
        }
    }
}

sqldelight {
    databases {
        // Read-only Japanese morphological-analysis pack (mecab-ipadic), built by tools/packs/build_tokenizer.py.
        create("TokenizerDatabase") {
            packageName.set("app.mokuhyo.lang.ja.tokenizer.db")
            srcDirs.setFrom("src/commonMain/sqldelightTokenizer")
        }
    }
}
