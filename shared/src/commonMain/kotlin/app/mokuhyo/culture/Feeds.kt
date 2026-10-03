package app.mokuhyo.culture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Current-events links (BRIEF_PHASE8 C-09): `packs/<lang>/feeds.json`, from tools/terms/feeds.json. Links only: the app
 * opens them in the learner's browser and never fetches or stores the pages.
 */
@Serializable
data class Feed(val title: String, val url: String, val publisher: String, val reachability: String? = null)

@Serializable
data class FeedPack(val format: String = "mokuhyo-feeds/1", val lang: String, val feeds: List<Feed> = emptyList()) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): FeedPack = json.decodeFromString(serializer(), text)
    }
}
