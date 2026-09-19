package app.tsumugi.android.app

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import app.tsumugi.android.R
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.tsumugi.android.features.exams.ExamSpec
import app.tsumugi.study.ShadowingSentence
import app.tsumugi.study.TodayBlockKind

enum class Tab(@StringRes val label: Int, val glyph: String) {
    TODAY(R.string.tab_today, "今"),
    REVIEWS(R.string.tab_reviews, "復"),
    LEARN(R.string.tab_learn, "学"),
    PRACTICE(R.string.tab_practice, "練"),
    ME(R.string.tab_me, "私"),
}

/** Every screen reachable in the app. Each tab keeps its own back stack of these. */
sealed interface Route {
    data object TabRoot : Route
    data object Dictionary : Route
    data class Lookup(val query: String) : Route
    data object Scan : Route
    data object Library : Route
    data class Read(val docId: String) : Route
    data object Feeds : Route
    data object Aozora : Route
    /** [todayBlock]: launched from Today, so finishing marks that block done. */
    data class WritingPractice(val kanji: List<String>, val todayBlock: TodayBlockKind? = null) : Route
    data object Handwriting : Route
    data class Entry(val id: Long) : Route
    data class Kanji(val literal: String) : Route
    data object Radicals : Route
    data object Lessons : Route
    /** [limit]: the Today review budget (G-01); the nonce gives each visit a fresh session. */
    data class Reviews(val limit: Int = 500, val nonce: Long = System.nanoTime()) : Route
    data object PathLevels : Route
    data class PathLevel(val level: Int) : Route
    data class PathItem(val id: String) : Route
    data object Settings : Route
    data object WordLists : Route
    data object Grammar : Route
    data class GrammarLevel(val level: Int) : Route
    data class GrammarPoint(val id: String) : Route
    data object GrammarLessons : Route
    data class WordList(val id: String) : Route
    data object Import : Route
    data object Sync : Route
    data object Licenses : Route
    data object AiSettings : Route
    data object Integrations : Route
    data object Export : Route
    data object Leaderboard : Route
    data object Recordings : Route
    data object ContentReview : Route
    data object PersonalCard : Route
    data object AudioPacks : Route

    // Phase 11: media decks, known words, lyrics (BRIEF_V2 §6.1, §6.3)
    data object Decks : Route
    data class CreateDeck(val source: app.tsumugi.android.features.decks.DeckSource, val nonce: Long = System.nanoTime()) : Route
    data class Deck(val id: String) : Route
    data class CoreDeck(val id: String) : Route
    data object KnownWords : Route
    data object Songs : Route
    data class Song(val id: String) : Route

    // Kana course (G-13)
    data object Kana : Route
    data class KanaLesson(val id: String) : Route
    data class KanaPlacement(val script: app.tsumugi.kana.KanaScript, val nonce: Long = System.nanoTime()) : Route

    // Practice. Routes that start a session carry a nonce so each visit gets a fresh ViewModel.
    data object Scenarios : Route
    data class Roleplay(val scenarioId: String, val nonce: Long = System.nanoTime()) : Route
    data object Dialogues : Route
    data class DialoguePlayer(val id: String) : Route
    data object MinimalPairs : Route
    data object Media : Route
    data class Pomodoro(val nonce: Long = System.nanoTime()) : Route
    data class Opi(val nonce: Long = System.nanoTime()) : Route
    data class Shadowing(val sentences: List<ShadowingSentence>, val nonce: Long = System.nanoTime()) : Route
    data class FreeTalk(val nonce: Long = System.nanoTime()) : Route
    data object Podcasts : Route
    data class Podcast(val id: String) : Route
    data class Episode(val id: String) : Route

    // Exams
    data object Exams : Route
    data class ExamRun(val spec: ExamSpec, val nonce: Long = System.nanoTime()) : Route
    data class Attempt(val id: String) : Route
}

/** Per-tab back stacks that survive configuration changes. */
class NavigationViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.TODAY)
        private set
    private val stacks = mutableStateMapOf<Tab, List<Route>>()

    fun stack(tab: Tab): List<Route> = stacks[tab] ?: listOf(Route.TabRoot)

    val current: Route get() = stack(tab).last()
    val canGoBack: Boolean get() = stack(tab).size > 1

    fun select(tab: Tab) {
        // Re-selecting the current tab pops to its root, like iOS.
        if (tab == this.tab) stacks[tab] = listOf(Route.TabRoot)
        this.tab = tab
    }

    fun push(route: Route) {
        stacks[tab] = stack(tab) + route
    }

    fun back() {
        if (canGoBack) stacks[tab] = stack(tab).dropLast(1)
    }

    /** Opens [route] on [tab] from outside the app (share sheet, text-selection menu), replacing that tab's stack. */
    fun open(tab: Tab, route: Route) {
        stacks[tab] = listOf(Route.TabRoot, route)
        this.tab = tab
    }

    /** Global search (reachable from every tab): opens the dictionary on the current tab's stack. */
    fun openSearch() = push(Route.Dictionary)
}
