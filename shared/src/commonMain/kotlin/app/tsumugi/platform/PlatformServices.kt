package app.tsumugi.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import io.ktor.client.engine.HttpClientEngine
import okio.FileSystem
import okio.Path
import okio.Source

/**
 * The small set of things shared code needs from the host OS. Created once by each app
 * (Android: `PlatformServices(context)`, iOS: `PlatformServices()`) and handed to [app.tsumugi.api.AppGraph].
 */
expect class PlatformServices {
    val platformName: String

    /** Writable, app-private directory for packs, recordings and other files. */
    val dataDir: Path

    /** The device file system (Okio's SYSTEM is platform-specific, so common code gets it from here). */
    val fileSystem: FileSystem

    /** A content pack (or its manifest) shipped inside the app bundle under `packs/`, or null if not bundled. */
    fun openBundled(fileName: String): Source?

    /** Driver for the writable user database. */
    fun userDatabaseDriver(): SqlDriver

    /**
     * A bundled pack as a plain file SQLite can open in place (iOS: inside the app bundle), or null when packs are
     * not stored as plain files (Android: compressed/offset inside the APK, so they're copied first) (F-16, D-052).
     */
    fun bundledPackPath(fileName: String): Path?

    /**
     * Read-only driver for a content pack: opened in place from the bundle when [bundledPackPath] has it, else the
     * installed copy at `dataDir/packs/fileName`. The pack is never written.
     */
    fun packDriver(schema: SqlSchema<QueryResult.Value<Unit>>, fileName: String): SqlDriver

    /** Driver for an arbitrary SQLite file we don't own the schema of (e.g. an Anki collection). */
    fun openSqlite(path: String): SqlDriver

    /** Keychain / Keystore-backed secret storage for API tokens (CLAUDE.md rule 6). */
    val secrets: Secrets

    /** HTTP engine for the few explicitly online features (integrations, model downloads, sync). */
    fun httpEngine(): HttpClientEngine
}

/** Bytes free for this app on the volume holding [path] (or its nearest existing parent); null when unknown. */
expect fun freeBytes(path: Path): Long?

/**
 * Keeps [path] (a directory of re-downloadable or re-creatable files: packs, models, temp audio) out of device
 * backups. iOS sets `NSURLIsExcludedFromBackupKey` (App Store Guideline 2.23, F-15); a no-op elsewhere.
 */
expect fun excludeFromBackup(path: Path)

/** Unicode NFC normalization (CLAUDE.md rule 7: store text as NFC, never blindly NFKC). */
expect fun normalizeNfc(text: String): String
