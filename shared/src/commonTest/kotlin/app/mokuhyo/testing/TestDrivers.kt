package app.mokuhyo.testing

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/** Fresh in-memory SQLite database with [schema] created. */
expect fun inMemoryDriver(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver
