package app.tsumugi.dictionary

import app.tsumugi.jp.Conjugation
import app.tsumugi.jp.FuriganaSegment
import app.tsumugi.jp.PitchAccent

data class KanjiForm(val text: String, val isCommon: Boolean, val tags: List<String>)

/** [appliesTo] empty = applies to every kanji form. */
data class KanaForm(val text: String, val isCommon: Boolean, val tags: List<String>, val appliesTo: List<String>)

/** [appliesKanji]/[appliesKana] empty = applies to every form. */
data class Sense(
    val partsOfSpeech: List<String>,
    val glosses: List<String>,
    val misc: List<String>,
    val fields: List<String>,
    val dialects: List<String>,
    val info: List<String>,
    val appliesKanji: List<String>,
    val appliesKana: List<String>,
    val related: List<String>,
)

data class DictionaryEntry(
    val id: Long,
    val kanji: List<KanjiForm>,
    val kana: List<KanaForm>,
    val senses: List<Sense>,
    val isCommon: Boolean,
    /** Unofficial JLPT level 5..1 (community lists; there is no official list since 2010). */
    val jlpt: Int?,
    val rank: Long,
) {
    /** Primary written form: first kanji form unless the word is usually written in kana. */
    val headword: String
        get() = if (kanji.isEmpty() || usuallyKana) kana.first().text else kanji.first().text

    val reading: String get() = kana.first().text

    val usuallyKana: Boolean get() = senses.firstOrNull()?.misc?.contains("uk") == true

    val glossPreview: String get() = senses.take(3).joinToString("; ") { it.glosses.joinToString(", ") }

    fun summary() = EntrySummary(id, headword, reading, glossPreview, isCommon, jlpt)
}

data class EntrySummary(
    val id: Long,
    val headword: String,
    val reading: String,
    val glossPreview: String,
    val isCommon: Boolean,
    val jlpt: Int?,
)

enum class MatchKind { EXACT, DEINFLECTED, PREFIX, ENGLISH }

data class SearchHit(val entry: EntrySummary, val match: MatchKind, val deinflection: List<String> = emptyList())

enum class SearchMode { EMPTY, JAPANESE, ROMAJI, ENGLISH, SENTENCE }

data class SearchResults(
    val query: String,
    val mode: SearchMode,
    val hits: List<SearchHit>,
    /** Populated in sentence mode: every word found in the query, in order. */
    val tokens: List<Token> = emptyList(),
) {
    companion object {
        val EMPTY = SearchResults("", SearchMode.EMPTY, emptyList())
    }
}

/** A span of text; [entryId] is null when no dictionary word covers it (punctuation, unknown words). */
data class Token(
    val surface: String,
    val start: Int,
    val end: Int,
    val entryId: Long?,
    val dictionaryForm: String?,
    val reading: String?,
    val deinflection: List<String> = emptyList(),
)

data class ExampleSentence(val id: Long, val japanese: String, val english: String, val jlpt: Int?)

data class EntryDetail(
    val entry: DictionaryEntry,
    val furigana: List<FuriganaSegment>,
    val pitch: List<PitchAccent>,
    val kanji: List<KanjiInfo>,
    val sentences: List<ExampleSentence>,
    val conjugations: List<ConjugatedForm>,
)

data class ConjugatedForm(val conjugation: Conjugation, val label: String, val text: String)

data class KanjiInfo(
    val literal: String,
    val grade: Int?,
    val strokeCount: Int,
    val frequency: Int?,
    /** Unofficial N-level (5..1); [jlptOld] is KANJIDIC2's pre-2010 4-level value. */
    val jlpt: Int?,
    val jlptOld: Int?,
    val heisig: Int?,
    val heisig6: Int?,
    val meanings: List<String>,
    val onyomi: List<String>,
    val kunyomi: List<String>,
    val nanori: List<String>,
) {
    /** Keyword shown in lists: the primary KANJIDIC2 meaning. */
    val keyword: String get() = meanings.firstOrNull().orEmpty()
}

data class KanjiStroke(val order: Int, val path: String, val type: String)

data class KanjiDetail(
    val info: KanjiInfo,
    val components: List<String>,
    val strokes: List<KanjiStroke>,
    val words: List<EntrySummary>,
)

/** [radical] is the search key; [display] the glyph to show (RADKFILE writes some radicals as stand-in kanji). */
data class Radical(val radical: String, val strokeCount: Int, val display: String, val name: String)

data class RadicalSearchResult(
    val kanji: List<KanjiInfo>,
    /** Radicals that still narrow the current result; everything else can be greyed out. */
    val compatibleRadicals: Set<String>,
)
