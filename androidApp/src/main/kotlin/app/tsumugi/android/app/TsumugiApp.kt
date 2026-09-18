package app.tsumugi.android.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.features.ComingSoonScreen
import app.tsumugi.android.features.dictionary.DictionaryNav
import app.tsumugi.android.features.dictionary.DictionarySearchScreen
import app.tsumugi.android.features.dictionary.EntryScreen
import app.tsumugi.android.features.dictionary.KanjiScreen
import app.tsumugi.android.features.dictionary.RadicalSearchScreen
import app.tsumugi.android.features.me.ImportScreen
import app.tsumugi.android.features.me.LicensesScreen
import app.tsumugi.android.features.me.MeDestination
import app.tsumugi.android.features.me.MeScreen
import app.tsumugi.android.features.me.SettingsScreen
import app.tsumugi.android.features.study.LessonScreen
import app.tsumugi.android.features.study.PathItemScreen
import app.tsumugi.android.features.study.PathLevelScreen
import app.tsumugi.android.features.study.PathLevelsScreen
import app.tsumugi.android.features.study.ReviewScreen
import app.tsumugi.android.features.today.TodayScreen
import app.tsumugi.android.ui.TsumugiTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TsumugiApp() {
    val nav: NavigationViewModel = viewModel()
    val dictionaryNav = DictionaryNav(
        openEntry = { nav.push(Route.Entry(it)) },
        openKanji = { nav.push(Route.Kanji(it)) },
        openRadicals = { nav.push(Route.Radicals) },
    )
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    TsumugiTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(nav.current.title.ifEmpty { nav.tab.label }) },
                    navigationIcon = { if (nav.canGoBack) TextButton(onClick = nav::back) { Text("‹ Back") } },
                    actions = { if (nav.current != Route.Dictionary) TextButton(onClick = nav::openSearch) { Text("Search") } },
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == nav.tab,
                            onClick = { nav.select(tab) },
                            icon = { Text(tab.glyph, style = MaterialTheme.typography.titleMedium) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (val route = nav.current) {
                    Route.TabRoot -> TabRoot(nav.tab, nav::push)
                    Route.Dictionary -> DictionarySearchScreen(dictionaryNav)
                    is Route.Entry -> EntryScreen(route.id, dictionaryNav)
                    is Route.Kanji -> KanjiScreen(route.literal, dictionaryNav)
                    Route.Radicals -> RadicalSearchScreen(dictionaryNav)
                    Route.Lessons -> LessonScreen(onDone = nav::back, onOpenItem = { nav.push(Route.PathItem(it)) })
                    Route.Reviews -> ReviewScreen(onDone = nav::back)
                    Route.PathLevels -> PathLevelsScreen(onOpenLevel = { nav.push(Route.PathLevel(it)) })
                    is Route.PathLevel -> PathLevelScreen(route.level, onOpenItem = { nav.push(Route.PathItem(it)) })
                    is Route.PathItem -> PathItemScreen(route.id, onOpenItem = { nav.push(Route.PathItem(it)) })
                    Route.Settings -> SettingsScreen()
                    Route.Import -> ImportScreen()
                    Route.Licenses -> LicensesScreen()
                }
            }
        }
    }
}

@Composable
private fun TabRoot(tab: Tab, push: (Route) -> Unit) {
    when (tab) {
        Tab.TODAY -> TodayScreen(onLessons = { push(Route.Lessons) }, onReviews = { push(Route.Reviews) })
        Tab.REVIEWS -> TodayScreen(onLessons = { push(Route.Lessons) }, onReviews = { push(Route.Reviews) })
        Tab.LEARN -> LearnHome(push)
        Tab.PRACTICE -> ComingSoonScreen(tab.label)
        Tab.ME -> MeScreen { d ->
            push(
                when (d) {
                    MeDestination.IMPORT -> Route.Import
                    MeDestination.SETTINGS -> Route.Settings
                    MeDestination.LICENSES -> Route.Licenses
                },
            )
        }
    }
}

@Composable
private fun LearnHome(push: (Route) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        listOf(
            Triple("Kanji path", "60 levels · radicals → kanji → vocabulary", Route.PathLevels),
            Triple("Dictionary", "Offline JMdict · kanji · examples", Route.Dictionary),
            Triple("Radical search", "Find a kanji by its parts", Route.Radicals),
        ).forEach { (title, subtitle, route) ->
            ListItem(
                modifier = Modifier.clickable { push(route) },
                headlineContent = { Text(title) },
                supportingContent = { Text(subtitle) },
            )
        }
    }
}
