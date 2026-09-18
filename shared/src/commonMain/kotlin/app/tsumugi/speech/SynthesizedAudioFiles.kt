package app.tsumugi.speech

import okio.FileSystem
import okio.Path
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Temporary WAV files for synthesized speech (VOICEVOX), one per synthesis so a new sentence never overwrites
 * audio that is still playing (F-30). The player deletes its file when playback ends ([delete]); anything left
 * behind (a crash, a missed callback) is removed by [prune] — old files on each [write], everything at launch.
 */
class SynthesizedAudioFiles(
    private val fs: FileSystem,
    val dir: Path,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    /** Writes [wav] to a new uniquely named file and returns its path. */
    fun write(wav: ByteArray): Path {
        fs.createDirectories(dir)
        prune(olderThanMs = maxAgeMs)
        val path = dir / "$PREFIX${Uuid.random()}$SUFFIX"
        fs.write(path) { write(wav) }
        return path
    }

    /** Deletes one file returned by [write]; paths outside [dir] are ignored. */
    fun delete(path: Path) {
        if (path.parent == dir && path.name.startsWith(PREFIX)) runCatching { fs.delete(path, mustExist = false) }
    }

    /** Deletes files older than [olderThanMs], or all of them when null (at launch, nothing is playing). */
    fun prune(olderThanMs: Long? = null) {
        val now = nowMs()
        fs.listOrNull(dir)?.filter { it.name.startsWith("voicevox") && it.name.endsWith(SUFFIX) }?.forEach { file ->
            val modified = fs.metadataOrNull(file)?.lastModifiedAtMillis ?: 0L
            if (olderThanMs == null || now - modified > olderThanMs) runCatching { fs.delete(file, mustExist = false) }
        }
    }

    companion object {
        /** Files older than this are removed on the next [write] (10 minutes: longer than any sentence plays). */
        const val DEFAULT_MAX_AGE_MS = 10 * 60 * 1000L
        private const val PREFIX = "voicevox-"
        private const val SUFFIX = ".wav"
    }
}
