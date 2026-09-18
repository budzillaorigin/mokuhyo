package app.tsumugi.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.net.URI
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/** Plain JDBC over HikariCP, migrated with Flyway (vendor-specific scripts under db/migration/<vendor>). */
class Db(private val config: Config) : AutoCloseable {
    val isSqlite = config.isSqlite
    private val dataSource: HikariDataSource

    init {
        val hikari = HikariConfig()
        if (isSqlite) {
            val path = config.database.removePrefix("jdbc:").removePrefix("sqlite:")
            hikari.jdbcUrl = "jdbc:sqlite:$path"
            // SQLite allows one writer; a single connection keeps transactions simple and correct.
            hikari.maximumPoolSize = 1
            hikari.connectionInitSql = "PRAGMA foreign_keys = ON"
        } else {
            val (url, user, password) = postgresUrl(config.database)
            hikari.jdbcUrl = url
            user?.let { hikari.username = it }
            password?.let { hikari.password = it }
            hikari.maximumPoolSize = 10
        }
        dataSource = HikariDataSource(hikari)
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/${if (isSqlite) "sqlite" else "postgres"}")
            .load()
            .migrate()
    }

    /** Runs [block] in a transaction on the IO dispatcher. */
    suspend fun <T> tx(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.block().also { c.commit() }
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }
    }

    override fun close() = dataSource.close()

    companion object {
        /** Accepts `postgres://user:pass@host:port/db` (docker style) or a `jdbc:postgresql://` URL. */
        fun postgresUrl(value: String): Triple<String, String?, String?> {
            if (value.startsWith("jdbc:")) return Triple(value, null, null)
            val uri = URI(value.replaceFirst("postgresql://", "postgres://"))
            val (user, password) = uri.userInfo?.split(':', limit = 2)?.let { it[0] to it.getOrNull(1) } ?: (null to null)
            val port = if (uri.port > 0) uri.port else 5432
            return Triple("jdbc:postgresql://${uri.host}:$port${uri.path}", user, password)
        }
    }
}

fun Connection.update(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { it.bind(args).executeUpdate() }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(args).executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out += map(rs)
            out
        }
    }

fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

private fun PreparedStatement.bind(args: Array<out Any?>): PreparedStatement {
    args.forEachIndexed { i, v ->
        when (v) {
            null -> setObject(i + 1, null)
            is ByteArray -> setBytes(i + 1, v)
            is Boolean -> setInt(i + 1, if (v) 1 else 0)
            else -> setObject(i + 1, v)
        }
    }
    return this
}
