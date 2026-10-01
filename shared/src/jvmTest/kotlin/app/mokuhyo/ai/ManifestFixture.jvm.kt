package app.mokuhyo.ai

import app.mokuhyo.testing.repoFile

actual object ManifestFixture {
    actual fun read(): String = repoFile("content/models/manifest.json").readText()
}
