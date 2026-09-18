package app.tsumugi.platform

import android.content.Context
import android.os.Build
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.tsumugi.db.TsumugiDatabase
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.source
import java.io.FileNotFoundException
import java.text.Normalizer

actual class PlatformServices(private val context: Context) {
    actual val platformName: String = "Android ${Build.VERSION.RELEASE}"

    actual val dataDir: Path = context.filesDir.toOkioPath()

    actual val fileSystem: FileSystem = FileSystem.SYSTEM

    actual fun openBundled(fileName: String): Source? =
        try {
            context.assets.open("packs/$fileName").source()
        } catch (_: FileNotFoundException) {
            null
        }

    actual fun userDatabaseDriver(): SqlDriver =
        AndroidSqliteDriver(TsumugiDatabase.Schema, context, "tsumugi.db")

    actual fun packDriver(schema: SqlSchema<QueryResult.Value<Unit>>, fileName: String): SqlDriver =
        AndroidSqliteDriver(schema, context, (dataDir / "packs" / fileName).toString())
}

actual fun normalizeNfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
