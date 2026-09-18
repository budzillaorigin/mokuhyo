package app.tsumugi.android.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

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
    data object Licenses : Route { override val title = "Licenses" }
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
