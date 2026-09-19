package app.tsumugi.audio

import kotlinx.serialization.Serializable

/**
 * `index.json` at the root of an `audio-<set>.zip` (written by `tools/packs/render_audio.py`, D-090/D-092).
 * [clips] maps a clip key ([AudioKeys]) to the archive entry holding its audio.
 */
@Serializable
data class AudioPackIndex(
    val format: Int,
    val set: String,
    val codec: String = "aac-lc",
    val container: String = "m4a",
    val sampleRate: Int = 24_000,
    val channels: Int = 1,
    val engine: String = "",
    /** Required credit lines, e.g. `VOICEVOX:春日部つむぎ` (also in docs/LICENSES.md). */
    val credits: List<String> = emptyList(),
    val clips: Map<String, AudioClipInfo>,
)

@Serializable
data class AudioClipInfo(
    /** Archive entry, e.g. `clips/0348db1e5b076446.m4a` (named by content; several keys may share one). */
    val file: String,
    val bytes: Long,
    val ms: Long = 0,
    /** VOICEVOX character that speaks the clip. */
    val voice: String = "",
    /** What is spoken (for word items: the kana reading plus carrier particle). */
    val text: String = "",
    /** Word items only: the accent set explicitly at render time (0 = 平板). */
    val downstep: Int? = null,
    /** Word items only: the written form shown to the learner. */
    val display: String? = null,
)

/**
 * `audio-manifest.json` next to the archives (content/packs, or the owner's download server). Each entry is also a
 * valid [app.tsumugi.content.PackFile] (file, version, sha256, bytes).
 */
@Serializable
data class AudioPackManifest(val format: Int = 1, val packs: List<AudioPackEntry>)

@Serializable
data class AudioPackEntry(
    val file: String,
    val set: String,
    val version: String,
    val sha256: String,
    val bytes: Long,
    val clips: Int = 0,
    val audioSeconds: Long = 0,
    val credits: List<String> = emptyList(),
)

/** An installed audio set, as the settings/download screen lists it. */
data class InstalledAudioPack(
    val set: AudioSet,
    /** `<format>-<sha256 prefix>` from the manifest, or `sha256:<prefix>` for a file installed without one. */
    val version: String,
    val clips: Int,
    val bytesOnDisk: Long,
    val credits: List<String>,
)

/** `items.json` in the pitch pack: the pitch-accent perception test (BRIEF_V2 §6.7, D-094). */
@Serializable
data class PitchTestItems(val format: Int = 1, val carrier: String = "が", val items: List<PitchTestItem>)

@Serializable
data class PitchTestItem(
    /** Stable id `p<JMdict id>`; its clip key is [AudioKeys.pitch]. */
    val id: String,
    val entryId: Long,
    val text: String,
    /** Hiragana. */
    val reading: String,
    val moraCount: Int,
    /** Kanjium downstep (0 = 平板); the clip is rendered with exactly this accent. */
    val downstep: Int,
    /** `heiban`, `atamadaka`, `nakadaka` or `odaka`. */
    val pattern: String,
    /** Same-kana group (the reading). */
    val group: String,
    val gloss: String,
    /** What the clip says: [reading] + carrier particle, so 平板 and 尾高 are distinguishable. */
    val spoken: String,
    val source: String = "kanjium",
    /** Other items with the same kana and a different accent. */
    val confusableWith: List<String> = emptyList(),
)

/** What an audio install is doing, for a progress bar (CLAUDE.md rule 15). */
data class AudioInstallProgress(val set: String, val phase: Phase, val bytesDone: Long, val bytesTotal: Long) {
    enum class Phase { DOWNLOADING, COPYING, EXTRACTING }

    val fraction: Double get() = if (bytesTotal <= 0) 0.0 else bytesDone.toDouble() / bytesTotal
}
