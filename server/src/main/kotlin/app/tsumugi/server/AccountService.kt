package app.tsumugi.server

/** Account settings, device registry (with installed pack versions) and per-user blobs. */
class AccountService(private val db: Db, private val config: Config) {

    suspend fun account(userId: String): Account = db.tx {
        queryOne(
            "SELECT id, email, display_name, e2e_enabled, e2e_salt, leaderboard_opt_in, email_verified FROM users WHERE id = ?",
            userId,
        ) {
            Account(
                it.getString(1), it.getString(2), it.getString(3), it.getInt(4) != 0, it.getString(5), it.getInt(6) != 0,
                emailVerified = it.getInt(7) != 0,
                emailVerificationRequired = config.requireEmailVerification,
            )
        }
            ?: unauthorized("unknown user")
    }

    suspend fun patch(userId: String, patch: AccountPatch): Account {
        db.tx {
            patch.displayName?.let { update("UPDATE users SET display_name = ? WHERE id = ?", it.trim().take(40), userId) }
            patch.e2eSalt?.let {
                if (it.length !in 16..256) badRequest("e2eSalt must be 16-256 characters")
                update("UPDATE users SET e2e_salt = ? WHERE id = ?", it, userId)
            }
            patch.e2eEnabled?.let { enabled ->
                if (enabled) {
                    val salt = queryOne("SELECT e2e_salt FROM users WHERE id = ?", userId) { it.getString(1) }
                    if (salt == null) badRequest("set e2eSalt before enabling end-to-end encryption")
                    // With E2E on the server keeps no readable stats for this user.
                    update("DELETE FROM review_facts WHERE user_id = ?", userId)
                }
                update("UPDATE users SET e2e_enabled = ? WHERE id = ?", enabled, userId)
            }
            patch.leaderboardOptIn?.let { update("UPDATE users SET leaderboard_opt_in = ? WHERE id = ?", it, userId) }
        }
        return account(userId)
    }

    suspend fun devices(userId: String): List<Device> = db.tx {
        query("SELECT id, name, platform, last_seen_at, packs FROM devices WHERE user_id = ? ORDER BY created_at", userId) {
            Device(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), decodePacks(it.getString(5)))
        }
    }

    suspend fun deleteDevice(userId: String, deviceId: String) {
        if (!db.tx { removeDevice(userId, deviceId) }) notFound("no such device")
    }

    suspend fun setPacks(userId: String, deviceId: String, packs: Map<String, String>) {
        val json = ServerJson.encodeToString(PacksRequest.serializer(), PacksRequest(packs))
        val updated = db.tx { update("UPDATE devices SET packs = ? WHERE id = ? AND user_id = ?", json, deviceId, userId) }
        if (updated == 0) notFound("no such device")
    }

    suspend fun putBlob(userId: String, id: String, contentType: String, data: ByteArray) {
        if (!BLOB_ID.matches(id)) badRequest("blob id must be 1-128 characters of [A-Za-z0-9._-]")
        if (data.size > config.maxBlobBytes) throw ApiException(413, "blob larger than ${config.maxBlobBytes} bytes")
        db.tx {
            val used = queryOne("SELECT COALESCE(SUM(size), 0) FROM blobs WHERE user_id = ? AND id <> ?", userId, id) { it.getLong(1) } ?: 0
            if (used + data.size > config.blobQuotaBytes) throw ApiException(413, "blob storage quota (${config.blobQuotaBytes} bytes) exceeded")
            update("DELETE FROM blobs WHERE user_id = ? AND id = ?", userId, id)
            update(
                "INSERT INTO blobs(user_id, id, content_type, size, data, updated_at) VALUES (?,?,?,?,?,?)",
                userId, id, contentType.take(100), data.size.toLong(), data, now(),
            )
        }
    }

    suspend fun getBlob(userId: String, id: String): Pair<String, ByteArray> = db.tx {
        queryOne("SELECT content_type, data FROM blobs WHERE user_id = ? AND id = ?", userId, id) { it.getString(1) to it.getBytes(2) }
    } ?: notFound("no such blob")

    suspend fun deleteBlob(userId: String, id: String) {
        db.tx { update("DELETE FROM blobs WHERE user_id = ? AND id = ?", userId, id) }
    }

    private fun decodePacks(json: String): Map<String, String> =
        runCatching { ServerJson.decodeFromString(PacksRequest.serializer(), json).packs }.getOrDefault(emptyMap())

    companion object {
        private val BLOB_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
