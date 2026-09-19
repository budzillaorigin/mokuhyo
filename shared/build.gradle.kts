plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.skie)
    alias(libs.plugins.sqldelight)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xexpect-actual-classes")
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
    }

    android {
        namespace = "app.tsumugi.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Types from these appear in the shared API (StateFlow, LocalDate), so apps see them too.
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.ktor.client.core)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        // Writable user database: progress, review log, notes. The only thing that syncs.
        create("TsumugiDatabase") {
            packageName.set("app.tsumugi.db")
            srcDirs.setFrom("src/commonMain/sqldelight")
            // Migrations live next to the schema as <from>.sqm; <version>.db snapshots are checked by
            // verifySqlDelightMigration (DECISIONS D-040).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
        // Read-only dictionary content pack, built by tools/packs/*.py from the same .sq schema.
        create("DictionaryDatabase") {
            packageName.set("app.tsumugi.dictionary.db")
            srcDirs.setFrom("src/commonMain/sqldelightDictionary")
        }
        // Read-only 60-level kanji path pack, built by tools/packs/build_kanji_path.py.
        create("PathDatabase") {
            packageName.set("app.tsumugi.path.db")
            srcDirs.setFrom("src/commonMain/sqldelightPath")
        }
        // Read-only grammar pack (N5→N1 points, examples, patterns), built by tools/packs/build_grammar.py.
        create("GrammarDatabase") {
            packageName.set("app.tsumugi.grammar.db")
            srcDirs.setFrom("src/commonMain/sqldelightGrammar")
        }
        // Read-only exam pack (JLPT blueprints, JLPT/DLPT item banks), built by tools/packs/build_exam.py.
        create("ExamDatabase") {
            packageName.set("app.tsumugi.exam.db")
            srcDirs.setFrom("src/commonMain/sqldelightExam")
        }
        // Read-only speaking/listening practice pack, built by tools/packs/build_practice.py.
        create("PracticeDatabase") {
            packageName.set("app.tsumugi.practice.db")
            srcDirs.setFrom("src/commonMain/sqldelightPractice")
        }
        // Read-only interest/domain tracks pack (BRIEF_V2 §6.5), built by tools/packs/build_tracks.py.
        create("TracksDatabase") {
            packageName.set("app.tsumugi.tracks.db")
            srcDirs.setFrom("src/commonMain/sqldelightTracks")
        }
        // Read-only graded-reader pack (stories, read-along lines, questions, genre tasks), built by
        // tools/packs/readers/build_readers.py.
        create("ReadersDatabase") {
            packageName.set("app.tsumugi.readers.db")
            srcDirs.setFrom("src/commonMain/sqldelightReaders")
        }
        // Read-only linguist pack (translation passages, poetry corner, reading-circle texts; BRIEF_V2 §6.12, §6.14),
        // built by tools/packs/build_translation.py and tools/packs/literature/build_literature.py.
        create("LinguistDatabase") {
            packageName.set("app.tsumugi.linguist.db")
            srcDirs.setFrom("src/commonMain/sqldelightLinguist")
        }
        // Read-only morphological-analysis pack (mecab-ipadic), built by tools/packs/build_tokenizer.py.
        create("TokenizerDatabase") {
            packageName.set("app.tsumugi.tokenizer.db")
            srcDirs.setFrom("src/commonMain/sqldelightTokenizer")
        }
    }
}
