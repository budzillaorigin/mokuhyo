package app.mokuhyo.desktop.ui

import androidx.compose.runtime.Composable
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.ui.exam.SkillScreen
import app.mokuhyo.desktop.ui.speaking.SpeakingScreen
import app.mokuhyo.exam.Skill

/** Screens before their phase lands: honest empty states, never fake content. */
@Composable
fun PlaceholderScreens(destination: Destination, app: AppGraph, navigate: (Destination) -> Unit) {
    when (destination) {
        Destination.HOME -> HomeDashboard(app, navigate)
        Destination.READING -> SkillScreen(app, Skill.READING)
        Destination.LISTENING -> SkillScreen(app, Skill.LISTENING)
        Destination.SPEAKING -> SpeakingScreen(app)
        Destination.REVIEW -> ReviewScreen(app)
        Destination.HISTORY -> HistoryList(app)
        Destination.SETTINGS -> SettingsScreen(app)
        else -> Page(destination.title) {
            EmptyState("Nothing here yet", "This section needs the content packs for your language, which aren't installed in this build.")
        }
    }
}

@Composable
fun HomeScreen(@Suppress("UNUSED_PARAMETER") app: AppGraph, @Suppress("UNUSED_PARAMETER") navigate: (Destination) -> Unit) {
    Page("Home", "Your estimated ILR level per skill, what to do next, and your review queue.") {
        EmptyState("No results yet", "Take a Reading or Listening test, or an interview in Speaking, to see your estimated level here.")
        Disclaimer()
    }
}
