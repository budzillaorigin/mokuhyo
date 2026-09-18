package app.tsumugi.media

import app.tsumugi.ai.Sha256
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.reader.FeedParser
import app.tsumugi.reader.ImportException
import app.tsumugi.reader.ParsedFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import kotlin.time.Clock

data class Podcast(
    val id: String,
    val url: String,
    val title: String,
    val author: String?,
    val imageUrl: String?,
    val addedAt: Long,
    val lastFetchedAt: Long?,
)

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DONE, FAILED }

data class PodcastEpisode(
    val id: String,
    val podcastId: String,
    val guid: String,
    val title: String,
    val published: String?,
    val audioUrl: String,
    val mime: String?,
    val lengthBytes: Long?,
    val durationMs: Long?,
    val summary: String,
    val downloadState: DownloadState,
    /** Absolute path of the downloaded file when [downloadState] is DONE. */
    val localPath: String?,
    val downloadedBytes: Long,
    val downloadError: String?,
    /** Content key once downloaded (for the transcript cache). */
    val mediaHash: String?,
    val positionMs: Long,
) {
    /** 0..1 while downloading, when the size is known. */
    val downloadFraction: Double? get() = lengthBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0) }
}

/**
 * Podcast subscriptions (BRIEF_V2 G-04, §6.10 Swotter-style podcasts): RSS feeds with audio enclosures, parsed by
 * the reader's [FeedParser]. Shared code keeps the episode list and the download bookkeeping; the platform does
 * the actual download (URLSession background task / WorkManager) into [targetFile] and reports through
 * [markDownloading] / [markDownloaded] / [markFailed]. Transcripts come from [SubtitleGenerator] on the
 * downloaded file, cached by its content key. Feeds and audio are fetched only when the learner asks
 * (device-local; nothing syncs).
 */
class PodcastService(
    private val db: TsumugiDatabase,
    private val fs: FileSystem,
    dataDir: Path,
    /** Fetches a feed's XML (the reader's UrlImporter.fetchText in the app, a fake in tests). */
    private val fetchText: suspend (String) -> String,
    private val clock: Clock = Clock.System,
) {
    val dir: Path = dataDir / DIR
    private val q get() = db.mediaQueries

    /** Subscribes to [url]; the feed must have at least one audio enclosure. Re-adding refreshes it. */
    @Throws(Exception::class)
    suspend fun subscribe(url: String): Podcast {
        val clean = url.trim()
        val parsed = FeedParser.parse(fetchText(clean))
        if (parsed.items.none { it.enclosure != null }) throw ImportException("That feed has no audio episodes")
        val id = feedId(clean)
        io { q.insertPodcast(id, clean, parsed.title.ifEmpty { clean }, parsed.author, parsed.imageUrl, now()) }
        store(id, parsed)
        return podcast(id)!!
    }

    /** Fetches the feed again and merges new or changed episodes (download state is kept). */
    @Throws(Exception::class)
    suspend fun refresh(podcastId: String): List<PodcastEpisode> {
        val p = podcast(podcastId) ?: throw ImportException("Unknown podcast")
        store(p.id, FeedParser.parse(fetchText(p.url)))
        return episodes(p.id)
    }

    @Throws(Exception::class)
    suspend fun podcasts(): List<Podcast> = io { q.podcasts().executeAsList().map { it.toPodcast() } }

    @Throws(Exception::class)
    suspend fun podcast(id: String): Podcast? = io { q.podcastById(id).executeAsOneOrNull()?.toPodcast() }

    @Throws(Exception::class)
    suspend fun episodes(podcastId: String): List<PodcastEpisode> = io { q.episodes(podcastId).executeAsList().map { it.toEpisode() } }

    @Throws(Exception::class)
    suspend fun episode(id: String): PodcastEpisode? = io { q.episodeById(id).executeAsOneOrNull()?.toEpisode() }

    /** Removes the subscription, its episode rows and every downloaded file. */
    @Throws(Exception::class)
    suspend fun unsubscribe(podcastId: String) = io {
        q.episodes(podcastId).executeAsList().forEach { e -> e.local_file?.let { runCatching { fs.delete(dir / it, mustExist = false) } } }
        q.deleteEpisodes(podcastId)
        q.deletePodcast(podcastId)
    }

    // --- Download bookkeeping (the platform downloads) --------------------------------------------------------

    /** Where the platform should write the episode's audio (the directory exists). */
    @Throws(Exception::class)
    suspend fun targetFile(episodeId: String): String = io {
        fs.createDirectories(dir)
        (dir / fileName(q.episodeById(episodeId).executeAsOne().let { it.id to it.audio_url })).toString()
    }

    @Throws(Exception::class)
    suspend fun queue(episodeId: String) = setState(episodeId, DownloadState.QUEUED, null, 0, null)

    @Throws(Exception::class)
    suspend fun markDownloading(episodeId: String, bytes: Long) = setState(episodeId, DownloadState.DOWNLOADING, null, bytes, null)

    /** The file is complete: computes its content key (for transcripts) off the main thread. */
    @Throws(Exception::class)
    suspend fun markDownloaded(episodeId: String): PodcastEpisode {
        val e = episode(episodeId) ?: throw ImportException("Unknown episode")
        val name = fileName(e.id to e.audioUrl)
        val size = io { fs.metadataOrNull(dir / name)?.size } ?: throw ImportException("The downloaded file is missing")
        setState(episodeId, DownloadState.DONE, name, size, null)
        val hash = io { MediaHash.ofBlocking(fs, dir / name) }
        io { q.setEpisodeHash(hash, episodeId) }
        return episode(episodeId)!!
    }

    @Throws(Exception::class)
    suspend fun markFailed(episodeId: String, error: String) = setState(episodeId, DownloadState.FAILED, null, 0, error)

    /** Deletes the downloaded file; the episode stays in the list. */
    @Throws(Exception::class)
    suspend fun deleteDownload(episodeId: String) {
        val e = episode(episodeId) ?: return
        io { runCatching { fs.delete(dir / fileName(e.id to e.audioUrl), mustExist = false) } }
        setState(episodeId, DownloadState.NONE, null, 0, null)
    }

    /** Episodes waiting for the platform downloader (QUEUED, and DOWNLOADING ones interrupted by a restart). */
    @Throws(Exception::class)
    suspend fun pendingDownloads(): List<PodcastEpisode> = io {
        (q.episodesInState(DownloadState.QUEUED.name).executeAsList() + q.episodesInState(DownloadState.DOWNLOADING.name).executeAsList()).map { it.toEpisode() }
    }

    @Throws(Exception::class)
    suspend fun setPosition(episodeId: String, positionMs: Long) = io { q.setEpisodePosition(positionMs.coerceAtLeast(0), episodeId) }

    /**
     * The episode's transcript through [generator] (cached by content key). [source] is the platform's decoder
     * over the downloaded file. Throws when the episode isn't downloaded.
     */
    @Throws(Exception::class)
    suspend fun transcript(
        episodeId: String,
        generator: SubtitleGenerator,
        source: PcmSource,
        onProgress: (SubtitleProgress) -> Unit = {},
    ): GeneratedSubtitles {
        val e = episode(episodeId) ?: throw ImportException("Unknown episode")
        val hash = e.mediaHash ?: throw ImportException("Download the episode first")
        return generator.generate(hash, source, "ja", onProgress)
    }

    // --- Internals ----------------------------------------------------------------------------------------

    private suspend fun store(podcastId: String, parsed: ParsedFeed) = io {
        db.transaction {
            q.podcastById(podcastId).executeAsOneOrNull()?.let {
                q.updatePodcast(parsed.title.ifEmpty { it.title }, parsed.author ?: it.author, parsed.imageUrl ?: it.image_url, now(), podcastId)
            }
            parsed.items.filter { it.enclosure != null }.distinctBy { it.guid ?: it.enclosure!!.url }.forEachIndexed { ord, item ->
                val enc = item.enclosure!!
                val guid = item.guid ?: enc.url
                val id = episodeId(podcastId, guid)
                q.insertEpisode(id, podcastId, guid, ord.toLong(), item.title, item.published, enc.url, enc.type, enc.lengthBytes, item.durationMs, item.summary, now())
                q.updateEpisodeMeta(ord.toLong(), item.title, item.published, enc.url, enc.type, enc.lengthBytes, item.durationMs, item.summary, id)
            }
        }
    }

    private suspend fun setState(id: String, state: DownloadState, file: String?, bytes: Long, error: String?) =
        io { q.setEpisodeDownload(state.name, file, bytes, error, id) }

    private fun app.tsumugi.db.Podcast_feed.toPodcast() = Podcast(id, url, title, author, image_url, added_at, last_fetched_at)

    private fun app.tsumugi.db.Podcast_episode.toEpisode() = PodcastEpisode(
        id, feed_id, guid, title, published, audio_url, mime, length_bytes, duration_ms, summary,
        runCatching { DownloadState.valueOf(download_state) }.getOrDefault(DownloadState.NONE),
        local_file?.let { (dir / it).toString() }, downloaded_bytes, download_error, media_hash, position_ms,
    )

    private fun now() = clock.now().toEpochMilliseconds()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DIR = "podcasts"

        fun feedId(url: String) = "pod-" + Sha256.hex(url.encodeToByteArray()).take(16)
        fun episodeId(podcastId: String, guid: String) = "ep-" + Sha256.hex("$podcastId$guid".encodeToByteArray()).take(20)

        /** `<episode id>.<ext from the URL>` (mp3 when the URL has none). */
        internal fun fileName(e: Pair<String, String>): String {
            val ext = e.second.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
                .lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp3"
            return "${e.first}.$ext"
        }
    }
}
