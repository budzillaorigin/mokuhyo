package app.tsumugi.integrations.wanikani

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * WaniKani API v2 shapes (revision 20170710), reduced to the fields Tsumugi uses. Mnemonic fields
 * (meaning_mnemonic, reading_mnemonic, meaning_hint, …) are intentionally not modelled, so they are never
 * even decoded, let alone stored (BRIEF §4 "Explicitly not used").
 */

@Serializable
data class WkPages(@SerialName("next_url") val nextUrl: String? = null)

@Serializable
data class WkCollection<T>(
    val pages: WkPages = WkPages(),
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("data_updated_at") val dataUpdatedAt: String? = null,
    val data: List<WkResource<T>> = emptyList(),
)

@Serializable
data class WkResource<T>(
    val id: Long = 0,
    @SerialName("object") val type: String,
    @SerialName("data_updated_at") val dataUpdatedAt: String? = null,
    val data: T,
)

@Serializable
data class WkUser(
    val username: String,
    val level: Int,
    val subscription: WkSubscription = WkSubscription(),
)

@Serializable
data class WkSubscription(
    val active: Boolean = false,
    val type: String = "free",
    @SerialName("max_level_granted") val maxLevelGranted: Int = 3,
)

@Serializable
data class WkMeaning(val meaning: String, val primary: Boolean = false, @SerialName("accepted_answer") val acceptedAnswer: Boolean = true)

@Serializable
data class WkReading(
    val reading: String,
    val primary: Boolean = false,
    @SerialName("accepted_answer") val acceptedAnswer: Boolean = true,
    /** Kanji only: onyomi, kunyomi or nanori. */
    val type: String? = null,
)

@Serializable
data class WkSubject(
    val level: Int = 0,
    /** Null for image-only radicals. */
    val characters: String? = null,
    val meanings: List<WkMeaning> = emptyList(),
    val readings: List<WkReading> = emptyList(),
    @SerialName("hidden_at") val hiddenAt: String? = null,
)

@Serializable
data class WkAssignment(
    @SerialName("subject_id") val subjectId: Long,
    @SerialName("subject_type") val subjectType: String,
    @SerialName("srs_stage") val srsStage: Int = 0,
    @SerialName("unlocked_at") val unlockedAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("passed_at") val passedAt: String? = null,
    @SerialName("burned_at") val burnedAt: String? = null,
    @SerialName("available_at") val availableAt: String? = null,
    val hidden: Boolean = false,
)

@Serializable
data class WkReviewStatistic(
    @SerialName("subject_id") val subjectId: Long,
    @SerialName("meaning_correct") val meaningCorrect: Int = 0,
    @SerialName("meaning_incorrect") val meaningIncorrect: Int = 0,
    @SerialName("reading_correct") val readingCorrect: Int = 0,
    @SerialName("reading_incorrect") val readingIncorrect: Int = 0,
)

/** The user's own notes and synonyms. These are the user's content, so importing them is allowed. */
@Serializable
data class WkStudyMaterial(
    @SerialName("subject_id") val subjectId: Long,
    @SerialName("meaning_note") val meaningNote: String? = null,
    @SerialName("reading_note") val readingNote: String? = null,
    @SerialName("meaning_synonyms") val meaningSynonyms: List<String> = emptyList(),
)

@Serializable
data class WkLevelProgression(
    val level: Int,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("passed_at") val passedAt: String? = null,
)

@Serializable
internal data class WkReviewBody(val review: WkReviewPost)

@Serializable
internal data class WkReviewPost(
    @SerialName("subject_id") val subjectId: Long,
    @SerialName("incorrect_meaning_answers") val incorrectMeaningAnswers: Int,
    @SerialName("incorrect_reading_answers") val incorrectReadingAnswers: Int,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
internal data class WkStudyMaterialBody(@SerialName("study_material") val studyMaterial: WkStudyMaterialUpdate)

@Serializable
data class WkStudyMaterialUpdate(
    @SerialName("meaning_note") val meaningNote: String? = null,
    @SerialName("reading_note") val readingNote: String? = null,
    @SerialName("meaning_synonyms") val meaningSynonyms: List<String>? = null,
)
