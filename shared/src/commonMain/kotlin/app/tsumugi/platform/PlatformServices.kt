package app.tsumugi.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
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

    /** A content pack (or its manifest) shipped inside the app bundle under `packs/`, or null if not bundled. */
    fun openBundled(fileName: String): Source?

    /** Driver for the writable user database. */
    fun userDatabaseDriver(): SqlDriver

    /** Driver for an installed, read-only content pack at `dataDir/packs/fileName`. */
    fun packDriver(schema: SqlSchema<QueryResult.Value<Unit>>, fileName: String): SqlDriver
}

/** Unicode NFC normalization (CLAUDE.md rule 7: store text as NFC, never blindly NFKC). */
expect fun normalizeNfc(text: String): String
