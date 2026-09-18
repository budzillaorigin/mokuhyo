package app.tsumugi.platform

import android.content.Context
import android.os.Build
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.integrations.anki.RawSqliteSchema
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.source
import java.io.File
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

    /** Assets live inside the APK (even `noCompress` ones sit at an offset in the zip), so SQLite can't open them. */
    actual fun bundledPackPath(fileName: String): Path? = null

    actual fun userDatabaseDriver(): SqlDriver =
        AndroidSqliteDriver(TsumugiDatabase.Schema, context, "tsumugi.db")

    /**
     * The framework SQLite API can't take `?immutable=1` or open flags through SupportSQLiteOpenHelper, so packs are
     * made read-only with `PRAGMA query_only` right after open (D-052). The file is app-private and replaced only by
     * PackInstaller's atomic rename.
     */
    actual fun packDriver(schema: SqlSchema<QueryResult.Value<Unit>>, fileName: String): SqlDriver =
        AndroidSqliteDriver(
            schema, context, (dataDir / "packs" / fileName).toString(),
            callback = object : AndroidSqliteDriver.Callback(schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.query("PRAGMA query_only = 1").close()
                }
            },
        )

    actual fun openSqlite(path: String): SqlDriver = AndroidSqliteDriver(RawSqliteSchema, context, path)

    actual val secrets: Secrets by lazy { SecretStore(context) }

    actual fun httpEngine(): HttpClientEngine = OkHttp.create()
}

actual fun normalizeNfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

actual fun freeBytes(path: Path): Long? {
    var dir: File? = path.toFile()
    while (dir != null && !dir.exists()) dir = dir.parentFile
    return dir?.usableSpace?.takeIf { it > 0 }
}

/** Android excludes large folders through `backup_rules.xml` / `data_extraction_rules.xml` in the app instead. */
actual fun excludeFromBackup(path: Path) = Unit
