package app.tsumugi.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.days

/**
 * The per-user append-only change log (docs/SYNC_PROTOCOL.md). The server never merges: it orders changes with a
 * per-user `seq`, dedupes retried pushes, and returns everything after a client's cursor. Payloads are opaque.
 */
class SyncService(private val db: Db, private val config: Config) {

    suspend fun push(userId: String, deviceId: String, changes: List<Change>): PushResponse {
        if (changes.size > config.maxPushChanges) throw ApiException(413, "at most ${config.maxPushChanges} changes per push")
        changes.forEach(::validate)
        return db.tx {
            // Lock this user's counter row first (a no-op UPDATE takes a row lock in Postgres and the write lock in
            // SQLite), so concurrent pushes from two devices get strictly increasing, gap-free sequence numbers.
            update("UPDATE seq_counters SET last_seq = last_seq WHERE user_id = ?", userId)
            var seq = queryOne("SELECT last_seq FROM seq_counters WHERE user_id = ?", userId) { it.getLong(1) }
                ?: unauthorized("unknown user")
            val e2e = queryOne("SELECT e2e_enabled FROM users WHERE id = ?", userId) { it.getInt(1) != 0 } ?: false
            for (c in changes) {
                val dedupe = dedupeKey(c)
                val exists = queryOne("SELECT 1 FROM changes WHERE user_id = ? AND dedupe = ?", userId, dedupe) { true } ?: false
                if (exists) continue
                seq++
                update(
                    """INSERT INTO changes(user_id, seq, tbl, row_key, op, row_json, sealed, updated_at, device_id, dedupe, created_at)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                    userId, seq, c.table, c.key, c.op, c.row?.let { ServerJson.encodeToString(JsonObject.serializer(), it) },
                    c.sealed, c.updatedAt, c.deviceId, dedupe, now(),
                )
                if (!e2e && c.table == "review" && c.op == OP_UPSERT && c.row != null) {
                    val ts = (c.row["ts"] as? JsonPrimitive)?.longOrNull ?: c.updatedAt
                    update(
                        "INSERT INTO review_facts(user_id, review_id, ts) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                        userId, c.key, ts,
                    )
                }
            }
            update("UPDATE seq_counters SET last_seq = ? WHERE user_id = ?", seq, userId)
            update("UPDATE devices SET last_seen_at = ? WHERE id = ? AND user_id = ?", now(), deviceId, userId)
            // `accepted` counts every valid change, including ones already stored by an earlier (retried) push:
            // the client may mark all of them synced.
            PushResponse(accepted = changes.size, lastSeq = seq)
        }
    }

    suspend fun pull(userId: String, since: Long, limit: Int): PullResponse {
        val capped = limit.coerceIn(1, MAX_PULL)
        return db.tx {
            val rows = query(
                """SELECT seq, tbl, row_key, op, row_json, sealed, updated_at, device_id FROM changes
                   WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?""",
                userId, since, capped + 1,
            ) { rs ->
                Change(
                    table = rs.getString(2),
                    key = rs.getString(3),
                    op = rs.getString(4),
                    row = rs.getString(5)?.let { ServerJson.parseToJsonElement(it) as JsonObject },
                    sealed = rs.getString(6),
                    updatedAt = rs.getLong(7),
                    deviceId = rs.getString(8),
                    seq = rs.getLong(1),
                )
            }
            val page = rows.take(capped)
            PullResponse(page, page.lastOrNull()?.seq ?: since, hasMore = rows.size > capped)
        }
    }

    /** Opt-in users without E2E encryption, ranked by reviews in the period; streak = consecutive UTC days. */
    suspend fun leaderboard(period: String): List<LeaderboardEntry> {
        val days = when (period) {
            "day" -> 1
            "week" -> 7
            "month" -> 30
            else -> badRequest("period must be day, week or month")
        }
        val since = now() - days.days.inWholeMilliseconds
        return db.tx {
            val users = query(
                "SELECT id, display_name FROM users WHERE leaderboard_opt_in = 1 AND e2e_enabled = 0",
            ) { it.getString(1) to (it.getString(2) ?: "Anonymous") }
            users.map { (id, name) ->
                val reviews = queryOne("SELECT COUNT(*) FROM review_facts WHERE user_id = ? AND ts >= ?", id, since) { it.getInt(1) } ?: 0
                val streakSince = now() - STREAK_LOOKBACK_DAYS.days.inWholeMilliseconds
                val days = query("SELECT ts FROM review_facts WHERE user_id = ? AND ts >= ?", id, streakSince) {
                    Instant.ofEpochMilli(it.getLong(1)).atZone(ZoneOffset.UTC).toLocalDate()
                }.toSet()
                LeaderboardEntry(name, reviews, streak(days))
            }.filter { it.reviews > 0 }.sortedWith(compareByDescending<LeaderboardEntry> { it.reviews }.thenByDescending { it.streak })
        }
    }

    private fun validate(c: Change) {
        if (c.table.isBlank() || c.table.length > 64) badRequest("bad table")
        if (c.key.isEmpty() || c.key.length > 512) badRequest("bad key")
        if (c.deviceId.isBlank()) badRequest("deviceId required")
        when (c.op) {
            OP_UPSERT -> if ((c.row == null) == (c.sealed == null)) badRequest("UPSERT needs exactly one of row or sealed")
            OP_DELETE -> Unit
            else -> badRequest("op must be UPSERT or DELETE")
        }
    }

    companion object {
        const val OP_UPSERT = "UPSERT"
        const val OP_DELETE = "DELETE"
        const val MAX_PULL = 5_000
        const val DEFAULT_PULL = 1_000
        private const val STREAK_LOOKBACK_DAYS = 400

        /** Retried pushes carry identical (table, key, updatedAt, deviceId, op) and are stored once. */
        fun dedupeKey(c: Change): String =
            AuthService.sha256(listOf(c.table, c.key, c.updatedAt.toString(), c.deviceId, c.op).joinToString(""))

        /** Consecutive days with reviews, ending today or (if nothing yet today) yesterday. */
        fun streak(days: Set<LocalDate>, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Int {
            var cursor = if (today in days) today else today.minusDays(1)
            var n = 0
            while (cursor in days) {
                n++
                cursor = cursor.minusDays(1)
            }
            return n
        }
    }
}
