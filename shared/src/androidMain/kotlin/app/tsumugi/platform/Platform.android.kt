package app.tsumugi.platform

import android.content.Context
import android.os.Build
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.tsumugi.db.TsumugiDatabase

private object AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.RELEASE}"
}

actual fun currentPlatform(): Platform = AndroidPlatform

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun create(): SqlDriver = AndroidSqliteDriver(TsumugiDatabase.Schema, context, "tsumugi.db")
}
