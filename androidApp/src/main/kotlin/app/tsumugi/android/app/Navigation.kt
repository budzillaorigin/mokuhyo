package app.tsumugi.android.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.tsumugi.android.features.exams.ExamSpec

enum class Tab(val label: String, val glyph: String) {
    TODAY("Today", "今"),
    REVIEWS("Reviews", "復"),
    LEARN("Learn", "学"),
    PRACTICE("Practice", "練"),
    ME("Me", "私"),
}

/** Every screen reachable in the app. Each tab keeps its own back stack of these. */
sealed interface Route {
    val title: String

    data object TabRoot : Route { override val title = "" }
    data object Dictionary : Route { override val title = "Dictionary" }
    data class Lookup(val query: String) : Route { override val title = "Dictionary" }
    data object Scan : Route { override val title = "Scan text" }
    data object Library : Route { override val title = "Reading" }
    data class Read(val docId: String) : Route { override val title = "Reader" }
    data object Feeds : Route { override val title = "Feeds" }
    data object Aozora : Route { override val title = "Aozora Bunko" }
    data class WritingPractice(val kanji: List<String>) : Route { override val title = "Writing" }
    data object Handwriting : Route { override val title = "Draw to search" }
    data class Entry(val id: Long) : Route { override val title = "Word" }
    data class Kanji(val literal: String) : Route { override val title = literal }
    data object Radicals : Route { override val title = "Radical search" }
    data object Lessons : Route { override val title = "Lessons" }
    data object Reviews : Route { override val title = "Reviews" }
    data object PathLevels : Route { override val title = "Kanji path" }
    data class PathLevel(val level: Int) : Route { override val title = "Level $level" }
    data class PathItem(val id: String) : Route { override val title = "Item" }
    data object Settings : Route { override val title = "Settings" }
    data object WordLists : Route { override val title = "Word lists" }
    data object Grammar : Route { override val title = "Grammar" }
    data class GrammarLevel(val level: Int) : Route { override val title = "N$level grammar" }
    data class GrammarPoint(val id: String) : Route { override val title = "Grammar" }
    data object GrammarLessons : Route { override val title = "Grammar lessons" }
    data class WordList(val id: String) : Route { override val title = "Word list" }
    data object Import : Route { override val title = "Import & export" }
    data object Sync : Route { override val title = "Sync" }
    data object Licenses : Route { override val title = "Licenses" }
    data object AiSettings : Route { override val title = "AI & speech" }

    // Practice. Routes that start a session carry a nonce so each visit gets a fresh ViewModel.
    data object Scenarios : Route { override val title = "Role-play" }
    data class Roleplay(val scenarioId: String, val nonce: Long = System.nanoTime()) : Route { override val title = "Role-play" }
    data object Dialogues : Route { override val title = "Dialogues" }
    data class DialoguePlayer(val id: String) : Route { override val title = "Dialogue" }
    data object MinimalPairs : Route { override val title = "Minimal pairs" }
    data object Media : Route { override val title = "Media player" }
    data class Pomodoro(val nonce: Long = System.nanoTime()) : Route { override val title = "Speaking session" }
    data class Opi(val nonce: Long = System.nanoTime()) : Route { override val title = "OPI practice" }

    // Exams
    data object Exams : Route { override val title = "Exams" }
    data class ExamRun(val spec: ExamSpec, val nonce: Long = System.nanoTime()) : Route { override val title = spec.title }
    data class Attempt(val id: String) : Route { override val title = "Attempt review" }
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

    /** Global search (reachable from every tab): opens the dictionary on the current tab's stack. */
    fun openSearch() = push(Route.Dictionary)
}
