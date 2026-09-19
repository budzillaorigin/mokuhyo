package app.tsumugi.server

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * All server configuration, read from environment variables (one `.env` file with docker compose).
 * Fair-use limits on the hosted instance are just values here, never separate code paths.
 */
data class Config(
    /** `postgres://user:pass@host:5432/db`, `jdbc:postgresql://…`, or `sqlite:./dev.db` / `sqlite::memory:`. */
    val database: String = "sqlite:./dev.db",
    val port: Int = 8080,
    val jwtSecret: String,
    val jwtIssuer: String = "tsumugi",
    val accessTokenTtl: Duration = 15.minutes,
    val refreshTokenTtl: Duration = 60.days,
    /** WebAuthn relying party: the domain users see, and the origins allowed to sign. */
    val rpId: String = "localhost",
    val rpName: String = "Tsumugi",
    val rpOrigins: Set<String> = setOf("http://localhost:8080"),
    /** Public base URL, used in email-verification links. */
    val publicUrl: String = "http://localhost:8080",
    val smtp: Smtp? = null,
    val maxPushChanges: Int = 5_000,
    val maxBodyBytes: Long = 8L * 1024 * 1024,
    val blobQuotaBytes: Long = 200L * 1024 * 1024,
    val maxBlobBytes: Long = 20L * 1024 * 1024,
    /** Requests per minute per user (or per IP when anonymous). */
    val rateLimitPerMinute: Int = 600,
    val allowRegistration: Boolean = true,
    /**
     * Accounts must confirm their email before they can sync (BRIEF_V2 Phase 14, D-310). On by default, which is
     * the production setting; `make dev` turns it off, and a single-user server without SMTP may too.
     */
    val requireEmailVerification: Boolean = true,
    /** How long a verification link works. */
    val verifyTokenTtl: Duration = 48.hours,
    /** The shortest gap between two verification emails for one account (the resend rate limit). */
    val verifyResendInterval: Duration = 2.minutes,
) {
    data class Smtp(val host: String, val port: Int, val username: String?, val password: String?, val from: String)

    val isSqlite: Boolean get() = database.startsWith("sqlite:") || database.startsWith("jdbc:sqlite:")

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun get(key: String) = env[key]?.takeIf { it.isNotBlank() }
            val secret = get("TSUMUGI_JWT_SECRET")
                ?: error("TSUMUGI_JWT_SECRET is required (a long random string, e.g. `openssl rand -hex 32`)")
            require(secret.length >= 32) { "TSUMUGI_JWT_SECRET must be at least 32 characters" }
            val smtp = get("TSUMUGI_SMTP_HOST")?.let { host ->
                Smtp(
                    host = host,
                    port = get("TSUMUGI_SMTP_PORT")?.toInt() ?: 587,
                    username = get("TSUMUGI_SMTP_USER"),
                    password = get("TSUMUGI_SMTP_PASSWORD"),
                    from = get("TSUMUGI_SMTP_FROM") ?: "tsumugi@$host",
                )
            }
            val defaults = Config(jwtSecret = secret)
            return defaults.copy(
                database = get("TSUMUGI_DB") ?: defaults.database,
                port = get("TSUMUGI_PORT")?.toInt() ?: defaults.port,
                rpId = get("TSUMUGI_RP_ID") ?: defaults.rpId,
                rpName = get("TSUMUGI_RP_NAME") ?: defaults.rpName,
                rpOrigins = get("TSUMUGI_RP_ORIGINS")?.split(',')?.map { it.trim() }?.toSet() ?: defaults.rpOrigins,
                publicUrl = get("TSUMUGI_PUBLIC_URL") ?: defaults.publicUrl,
                smtp = smtp,
                maxPushChanges = get("TSUMUGI_MAX_PUSH_CHANGES")?.toInt() ?: defaults.maxPushChanges,
                maxBodyBytes = get("TSUMUGI_MAX_BODY_BYTES")?.toLong() ?: defaults.maxBodyBytes,
                blobQuotaBytes = get("TSUMUGI_BLOB_QUOTA_BYTES")?.toLong() ?: defaults.blobQuotaBytes,
                maxBlobBytes = get("TSUMUGI_MAX_BLOB_BYTES")?.toLong() ?: defaults.maxBlobBytes,
                rateLimitPerMinute = get("TSUMUGI_RATE_LIMIT_PER_MINUTE")?.toInt() ?: defaults.rateLimitPerMinute,
                allowRegistration = get("TSUMUGI_ALLOW_REGISTRATION")?.toBooleanStrictOrNull() ?: defaults.allowRegistration,
                requireEmailVerification = get("TSUMUGI_REQUIRE_EMAIL_VERIFICATION")?.toBooleanStrictOrNull() ?: defaults.requireEmailVerification,
                verifyTokenTtl = get("TSUMUGI_VERIFY_TOKEN_TTL_HOURS")?.toLong()?.hours ?: defaults.verifyTokenTtl,
                verifyResendInterval = get("TSUMUGI_VERIFY_RESEND_SECONDS")?.toLong()?.seconds ?: defaults.verifyResendInterval,
            )
        }
    }
}
