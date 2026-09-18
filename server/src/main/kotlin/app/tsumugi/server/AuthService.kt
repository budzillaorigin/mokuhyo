package app.tsumugi.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.Date
import java.util.UUID

/** Accounts, Argon2id passwords, access JWTs and rotating refresh tokens with family revocation on reuse. */
class AuthService(private val db: Db, private val config: Config, private val mail: Mailer) {
    private val random = SecureRandom()
    private val argon2 = Argon2Function.getInstance(ARGON_MEMORY_KB, ARGON_ITERATIONS, 1, 32, Argon2.ID)
    val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    suspend fun register(req: RegisterRequest): String {
        if (!config.allowRegistration) throw ApiException(403, "registration is closed on this server")
        val email = req.email.trim().lowercase()
        if (!EMAIL.matches(email)) badRequest("invalid email")
        if (req.password.length < MIN_PASSWORD) badRequest("password must be at least $MIN_PASSWORD characters")
        val hash = Password.hash(req.password).addRandomSalt(16).with(argon2).result
        val userId = UUID.randomUUID().toString()
        val verifyToken = token()
        db.tx {
            if (queryOne("SELECT id FROM users WHERE email = ?", email) { it.getString(1) } != null) {
                throw ApiException(409, "an account with this email already exists")
            }
            update(
                "INSERT INTO users(id, email, password_hash, display_name, verify_token, created_at) VALUES (?,?,?,?,?,?)",
                userId, email, hash, req.displayName?.trim()?.take(40), verifyToken, now(),
            )
            update("INSERT INTO seq_counters(user_id, last_seq) VALUES (?, 0)", userId)
        }
        mail.sendVerification(email, "${config.publicUrl}/v1/auth/verify?token=$verifyToken")
        return userId
    }

    suspend fun verifyEmail(token: String): Boolean = db.tx {
        update("UPDATE users SET email_verified = 1, verify_token = NULL WHERE verify_token = ?", token) > 0
    }

    suspend fun login(req: LoginRequest): TokenResponse {
        val email = req.email.trim().lowercase()
        val user = db.tx {
            queryOne("SELECT id, password_hash FROM users WHERE email = ?", email) { it.getString(1) to it.getString(2) }
        }
        // Check a dummy hash when the account doesn't exist, so timing doesn't reveal which emails are registered.
        val ok = Password.check(req.password, user?.second ?: DUMMY_HASH).with(argon2)
        if (user == null || !ok) unauthorized("wrong email or password")
        val deviceId = registerDevice(user.first, req.deviceName, req.platform)
        return issue(user.first, deviceId, familyId = UUID.randomUUID().toString())
    }

    suspend fun registerDevice(userId: String, name: String, platform: String): String {
        val id = UUID.randomUUID().toString()
        db.tx {
            update(
                "INSERT INTO devices(id, user_id, name, platform, last_seen_at, created_at) VALUES (?,?,?,?,?,?)",
                id, userId, name.take(60), platform.take(20), now(), now(),
            )
        }
        return id
    }

    /** Rotates a refresh token. Presenting an already-rotated token revokes the whole family (likely theft). */
    suspend fun refresh(refreshToken: String): TokenResponse {
        val hash = sha256(refreshToken)
        val outcome = db.tx {
            val row = queryOne(
                "SELECT user_id, device_id, family_id, expires_at, used, revoked FROM refresh_tokens WHERE token_hash = ?",
                hash,
            ) { RefreshRow(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getInt(5) != 0, it.getInt(6) != 0) }
                ?: return@tx null
            when {
                row.revoked || row.expiresAt < now() -> null
                row.used -> {
                    update("UPDATE refresh_tokens SET revoked = 1 WHERE family_id = ?", row.familyId)
                    null
                }
                else -> {
                    update("UPDATE refresh_tokens SET used = 1 WHERE token_hash = ?", hash)
                    update("UPDATE devices SET last_seen_at = ? WHERE id = ?", now(), row.deviceId)
                    row
                }
            }
        } ?: unauthorized("invalid refresh token")
        return issue(outcome.userId, outcome.deviceId, outcome.familyId)
    }

    suspend fun logout(refreshToken: String) {
        db.tx {
            val family = queryOne("SELECT family_id FROM refresh_tokens WHERE token_hash = ?", sha256(refreshToken)) { it.getString(1) }
            if (family != null) update("UPDATE refresh_tokens SET revoked = 1 WHERE family_id = ?", family)
        }
    }

    suspend fun issue(userId: String, deviceId: String, familyId: String): TokenResponse {
        val refresh = token()
        db.tx {
            update(
                "INSERT INTO refresh_tokens(token_hash, user_id, device_id, family_id, expires_at, created_at) VALUES (?,?,?,?,?,?)",
                sha256(refresh), userId, deviceId, familyId, now() + config.refreshTokenTtl.inWholeMilliseconds, now(),
            )
        }
        val access = JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject(userId)
            .withClaim(CLAIM_DEVICE, deviceId)
            .withExpiresAt(Date(now() + config.accessTokenTtl.inWholeMilliseconds))
            .sign(algorithm)
        return TokenResponse(access, refresh, deviceId, config.accessTokenTtl.inWholeSeconds)
    }

    private data class RefreshRow(val userId: String, val deviceId: String, val familyId: String, val expiresAt: Long, val used: Boolean, val revoked: Boolean)

    private fun token(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        const val CLAIM_DEVICE = "did"
        const val MIN_PASSWORD = 10
        private const val ARGON_MEMORY_KB = 19_456 // OWASP's Argon2id minimum (19 MiB, 2 iterations)
        private const val ARGON_ITERATIONS = 2
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val DUMMY_HASH: String = Password.hash("not-a-real-password")
            .addRandomSalt(16).with(Argon2Function.getInstance(ARGON_MEMORY_KB, ARGON_ITERATIONS, 1, 32, Argon2.ID)).result

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

fun now(): Long = System.currentTimeMillis()

/** Deletes a device and revokes its sessions. */
fun Connection.removeDevice(userId: String, deviceId: String): Boolean {
    update("UPDATE refresh_tokens SET revoked = 1 WHERE user_id = ? AND device_id = ?", userId, deviceId)
    return update("DELETE FROM devices WHERE id = ? AND user_id = ?", deviceId, userId) > 0
}
