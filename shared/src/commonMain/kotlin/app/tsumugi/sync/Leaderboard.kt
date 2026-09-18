package app.tsumugi.sync

import app.tsumugi.settings.SettingsRepository

enum class LeaderboardPeriod(val wire: String) { DAY("day"), WEEK("week"), MONTH("month") }

/** What the leaderboard screen shows (BRIEF §5.12, BRIEF_V2 G-11). Every state but [Rows] explains itself in the UI. */
sealed interface LeaderboardState {
    /** Sync isn't set up: the leaderboard lives on the learner's own sync server. */
    data object NotSignedIn : LeaderboardState

    /** Off (the default). The learner can opt in with a display name. */
    data object OptedOut : LeaderboardState

    /** End-to-end encrypted accounts never appear on leaderboards (the server can't count their reviews). */
    data object Encrypted : LeaderboardState

    data class Rows(val period: LeaderboardPeriod, val rows: List<LeaderboardRow>, val displayName: String?) : LeaderboardState

    data class Failed(val message: String) : LeaderboardState
}

/**
 * The opt-in leaderboard client (BRIEF §5.12, DECISIONS D-107) against the sync server's `GET /v1/leaderboard`
 * endpoint. Off by default and never contacted until the learner opts in: opting in sets `leaderboardOptIn` on the
 * server account (display name only; the server counts the clear-text review facts it already has) and the synced
 * preference [SettingsRepository] key [OPT_IN], so every device agrees. E2E accounts are excluded by the server.
 */
class LeaderboardService(
    private val client: () -> HttpSyncClient?,
    private val e2eEnabled: () -> Boolean,
    private val settings: SettingsRepository,
) {
    @Throws(Exception::class)
    suspend fun isOptedIn(): Boolean = settings.bool(OPT_IN, false)

    /**
     * Opts in (with an optional new [displayName]) or out. Updates the server first, so a failure leaves the
     * preference unchanged; throws [SyncException] when not signed in or the server refuses.
     */
    @Throws(Exception::class)
    suspend fun setOptIn(on: Boolean, displayName: String? = null): SyncAccountInfo {
        val c = client() ?: throw SyncException("Not signed in")
        if (on && e2eEnabled()) throw SyncException("End-to-end encrypted accounts can't join the leaderboard")
        val info = c.updateAccount(AccountPatch(displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }, leaderboardOptIn = on))
        settings.put(OPT_IN, on.toString())
        return info
    }

    /** The leaderboard for [period]; no network call unless signed in and opted in. */
    @Throws(Exception::class)
    suspend fun load(period: LeaderboardPeriod = LeaderboardPeriod.WEEK): LeaderboardState {
        val c = client() ?: return LeaderboardState.NotSignedIn
        if (e2eEnabled()) return LeaderboardState.Encrypted
        if (!isOptedIn()) return LeaderboardState.OptedOut
        return try {
            val account = c.account()
            if (!account.leaderboardOptIn) {
                // Opted out elsewhere (another client, the server admin): follow the server.
                settings.put(OPT_IN, "false")
                return LeaderboardState.OptedOut
            }
            LeaderboardState.Rows(period, c.leaderboard(period.wire), account.displayName)
        } catch (e: SyncException) {
            LeaderboardState.Failed(e.message ?: "The server refused the request")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LeaderboardState.Failed(e.message ?: "Couldn't reach the sync server")
        }
    }

    companion object {
        /** Synced learner preference; absent = off. */
        const val OPT_IN = "social.leaderboardOptIn"
    }
}
