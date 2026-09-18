package app.tsumugi.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.tsumugi.db.TsumugiDatabase
import platform.UIKit.UIDevice

private object IosPlatform : Platform {
    override val name: String =
        "${UIDevice.currentDevice.systemName()} ${UIDevice.currentDevice.systemVersion}"
}

actual fun currentPlatform(): Platform = IosPlatform

actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = NativeSqliteDriver(TsumugiDatabase.Schema, "tsumugi.db")
}
