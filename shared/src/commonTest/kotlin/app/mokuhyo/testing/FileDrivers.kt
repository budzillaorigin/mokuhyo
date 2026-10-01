package app.mokuhyo.testing

import app.cash.sqldelight.db.SqlDriver
import okio.FileSystem
import okio.Path

/** A driver on an existing (or new) SQLite file at an absolute path, with no schema management. */
expect fun fileDriver(path: String): SqlDriver

/** The real file system (Okio's SYSTEM is platform-specific). */
expect val testFileSystem: FileSystem

/** A fresh, empty scratch directory for one test. */
expect fun newTempDir(prefix: String): Path
