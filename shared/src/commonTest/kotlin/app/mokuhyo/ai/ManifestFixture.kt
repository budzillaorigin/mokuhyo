package app.mokuhyo.ai

/** Reads the repo's content/models/manifest.json (tests run with the repo as an ancestor of the working dir). */
expect object ManifestFixture {
    fun read(): String
}
