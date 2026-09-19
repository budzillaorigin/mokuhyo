package app.tsumugi.l10n

import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.ExamMode
import app.tsumugi.exam.jlpt.JlptItemType
import app.tsumugi.srs.Rating
import app.tsumugi.study.AnswerMode
import app.tsumugi.study.LearningPhase
import app.tsumugi.study.TodayBlockKind
import kotlin.concurrent.Volatile

/** UI languages the shared string table covers (BRIEF_V2 G-14). Learning content stays as authored. */
enum class AppLocale(val tag: String) {
    EN("en"), JA("ja");

    companion object {
        /** "ja", "ja-JP", "ja_JP" → [JA]; anything else → [EN]. */
        fun of(tag: String?): AppLocale = if (tag?.lowercase()?.startsWith("ja") == true) JA else EN
    }
}

/**
 * The UI language shared text producers use when the caller doesn't pass one (Today block titles, reminder text,
 * review prompt labels). Both apps set it at startup and when the learner changes the app language:
 * Swift `L10n.shared.setLanguage(tag: Locale.preferredLanguages.first)`, Android `L10n.setLanguage(tag)`.
 */
object L10n {
    @Volatile
    var locale: AppLocale = AppLocale.EN

    fun setLanguage(tag: String?) {
        locale = AppLocale.of(tag)
    }
}

/**
 * The shared string table for shared-core labels (DECISIONS D-109): SRS stages, item kinds, card directions, answer
 * modes, Today blocks, learning phases, exam kinds/modes/item types, ratings, and the few sentences shared code
 * produces (Today details, reminders, weekly challenges). Keyed by enum + locale; both apps map through it instead
 * of keeping their own copies. English learning content (meanings, explanations) is not in here and is never
 * translated.
 */
object Labels {
    fun stage(stage: Stage, locale: AppLocale = L10n.locale): String = text("stage.${stage.name}", locale)
    fun kind(kind: ItemKind, locale: AppLocale = L10n.locale): String = text("kind.${kind.name}", locale)
    fun direction(direction: CardDirection, locale: AppLocale = L10n.locale): String = text("direction.${direction.name}", locale)
    fun mode(mode: AnswerMode, locale: AppLocale = L10n.locale): String = text("mode.${mode.name}", locale)
    fun block(block: TodayBlockKind, locale: AppLocale = L10n.locale): String = text("block.${block.name}", locale)
    fun phase(phase: LearningPhase, locale: AppLocale = L10n.locale): String = text("phase.${phase.name}", locale)
    fun rating(rating: Rating, locale: AppLocale = L10n.locale): String = text("rating.${rating.name}", locale)
    fun examKind(kind: ExamKind, locale: AppLocale = L10n.locale): String = text("exam.${kind.name}", locale)
    fun examMode(mode: ExamMode, locale: AppLocale = L10n.locale): String = text("examMode.${mode.name}", locale)

    /** JLPT item types keep their official Japanese names in Japanese and use the English names otherwise. */
    fun itemType(type: JlptItemType, locale: AppLocale = L10n.locale): String = if (locale == AppLocale.JA) type.title else type.english

    /**
     * A sentence from the table with `{0}`, `{1}`… replaced by [args]. English plurals: `{0|review|reviews}` picks
     * the form by argument 0. Unknown keys return the key itself (tests catch those; see [keys]).
     */
    fun text(key: String, locale: AppLocale = L10n.locale, vararg args: Any): String {
        val entry = table[key] ?: return key
        var s = if (locale == AppLocale.JA) entry.second else entry.first
        s = PLURAL.replace(s) { m ->
            val n = args.getOrNull(m.groupValues[1].toInt())?.toString()?.toLongOrNull()
            if (n == 1L) m.groupValues[2] else m.groupValues[3]
        }
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }

    /** Every key in the table (for completeness tests). */
    val keys: Set<String> get() = table.keys

    private val PLURAL = Regex("""\{(\d+)\|([^|}]*)\|([^}]*)\}""")

    private val table: Map<String, Pair<String, String>> = buildMap {
        fun put(key: String, en: String, ja: String) = put(key, en to ja)

        put("stage.APPRENTICE", "Apprentice", "見習い")
        put("stage.GURU", "Guru", "達人")
        put("stage.MASTER", "Master", "名人")
        put("stage.ENLIGHTENED", "Enlightened", "悟り")
        put("stage.BURNED", "Burned", "焼却済み")

        put("kind.RADICAL", "Radical", "部首")
        put("kind.KANJI", "Kanji", "漢字")
        put("kind.VOCAB", "Vocabulary", "語彙")
        put("kind.GRAMMAR", "Grammar", "文法")
        put("kind.SENTENCE", "Sentence", "文")
        put("kind.LISTENING", "Listening", "聴解")
        put("kind.WRITING", "Writing", "書き取り")
        put("kind.MINIMAL_PAIR", "Minimal pair", "ミニマルペア")
        put("kind.CUSTOM", "Card", "カード")
        put("kind.KANA", "Kana", "かな")

        put("direction.RECOGNITION", "Recognition", "認識")
        put("direction.RECALL", "Recall", "想起")
        put("direction.MEANING", "Meaning", "意味")
        put("direction.READING", "Reading", "読み")
        put("direction.WRITING", "Write it", "書く")
        put("direction.LISTENING", "Listening", "聞き取り")
        put("direction.CLOZE", "Fill the gap", "穴埋め")
        put("direction.PRODUCTION", "Production", "作文")
        put("direction.GHOST", "Ghost review", "ゴースト復習")

        put("mode.MEANING", "Meaning", "意味")
        put("mode.READING", "Reading", "読み")
        put("mode.SELF_GRADED", "Recognition", "認識")
        put("mode.CLOZE", "Fill the gap", "穴埋め")
        put("mode.FILL_HINT", "Fill the gap (with hint)", "穴埋め（ヒント付き）")
        put("mode.BUILD", "Build the sentence", "文を組み立てる")
        put("mode.MEANING_CHOICE", "What does it mean?", "意味を選ぶ")
        put("mode.PRODUCTION", "Say it in Japanese", "日本語で書く")
        put("mode.WRITING", "Write it", "書く")
        put("mode.MINIMAL_PAIR", "What do you hear?", "どちらが聞こえる？")

        put("block.REVIEWS", "Reviews", "復習")
        put("block.LESSONS", "New kanji & vocabulary", "新しい漢字と語彙")
        put("block.GRAMMAR", "Grammar", "文法")
        put("block.IMMERSION", "Immersion", "多読・多聴")
        put("block.SHADOWING", "Shadowing", "シャドーイング")
        put("block.SPEAKING", "Speaking moment", "会話タイム")
        put("block.WRITING", "Writing", "書き取り")

        put("phase.FOUNDATIONS", "Foundations", "基礎")
        put("phase.CORE", "Core", "コア")
        put("phase.INTERMEDIATE", "Intermediate", "中級")
        put("phase.ADVANCED", "Advanced", "上級")

        put("rating.AGAIN", "Again", "もう一度")
        put("rating.HARD", "Hard", "難しい")
        put("rating.GOOD", "Good", "正解")
        put("rating.EASY", "Easy", "簡単")

        put("exam.JLPT", "JLPT", "日本語能力試験")
        put("exam.DLPT_READING", "DLPT Reading", "DLPT 読解")
        put("exam.DLPT_LISTENING", "DLPT Listening", "DLPT 聴解")
        put("exam.OPI", "OPI", "OPI")
        put("examMode.MOCK", "Full mock", "模擬試験")
        put("examMode.SECTION", "Section drill", "セクション練習")
        put("examMode.TYPE", "Item-type drill", "問題形式練習")
        put("examMode.FULL", "Full length", "フルレングス")
        put("examMode.SLICE_60", "60-minute slice", "60分版")
        put("examMode.SLICE_30", "30-minute slice", "30分版")
        put("examMode.INTERVIEW", "Interview", "インタビュー")

        // Today details.
        put("today.reviews.capped", "{0} of {1} due (the rest tomorrow)", "{1}件中{0}件（残りは明日）")
        put("today.reviews.due", "{0} due", "{0}件")
        put("today.reviews.capReached", "Today's review budget is used up ({0} answered)", "今日の復習枠は終わりました（{0}件回答）")
        put("today.lessons.count", "{0} {0|lesson|lessons} (level {1})", "{0}レッスン（レベル{1}）")
        put("today.lessons.doneToday", "{0} done today", "今日{0}件完了")
        put("today.kana.count", "Kana: {0} {0|lesson|lessons} first", "まず仮名：{0}レッスン")
        put("today.lessons.locked", "Nothing unlocked yet — reviews unlock more", "まだ解放されていません。復習でさらに解放されます")
        put("today.lessons.paused", "Paused while reviews catch up", "復習が追いつくまで一時停止")
        put("today.grammar.count", "{0} new {0|point|points}", "新しい文型{0}つ")
        put("today.grammar.doneToday", "{0} learned today", "今日{0}つ習得")
        put("today.immersion.detail", "{0} · {1}", "{0}・{1}")
        put("today.immersion.none", "Add a text to the reader to fill this block", "リーダーに文章を追加するとここに表示されます")
        put("today.shadowing.count", "{0} sentences from today's grammar", "今日の文法から{0}文")
        put("today.speaking.detail", "{0}", "{0}")
        put("today.writing.detail", "Write {0}", "{0}を書く")
        put("today.done", "Done", "完了")

        // Weekly challenges.
        put("challenge.studyDays", "Study on {0} days this week", "今週{0}日勉強する")
        put("challenge.reviews", "Answer {0} reviews this week", "今週{0}件復習する")
        put("challenge.newItems", "Learn {0} new items this week", "今週新しい項目を{0}個覚える")
        put("challenge.blocks", "Finish {0} Today blocks this week", "今週「今日」のブロックを{0}個終える")
        put("challenge.immersion", "Finish the immersion block on {0} days", "{0}日間、多読・多聴ブロックを終える")
        put("challenge.speaking", "Finish {0} speaking or shadowing blocks", "会話かシャドーイングのブロックを{0}回終える")
        put("challenge.games", "Score {0} points in Reflex and Atom this week", "今週リフレックスとアトムで{0}点取る")

        // Reminders.
        put("reminder.title", "Reviews are ready", "復習の時間です")
        put(
            "reminder.body",
            "{0} {0|review|reviews} waiting. A few minutes keeps your streak going.",
            "{0}件の復習が待っています。数分で連続記録を続けられます。",
        )
    }
}
