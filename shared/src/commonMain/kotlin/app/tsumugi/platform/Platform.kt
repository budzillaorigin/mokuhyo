package app.tsumugi.platform

import app.cash.sqldelight.db.SqlDriver

interface Platform {
    val name: String
}

expect fun currentPlatform(): Platform

/** Creates the writable user database driver. Read-only content packs are ATTACHed later (Phase 1). */
expect class DatabaseDriverFactory {
    fun create(): SqlDriver
}
