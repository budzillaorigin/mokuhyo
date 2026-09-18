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
                }
            }
        }
    }
}

@Composable
private fun TabRoot(tab: Tab, push: (Route) -> Unit) {
    when (tab) {
        Tab.TODAY -> TodayScreen()
        Tab.LEARN -> LearnHome(push)
        else -> ComingSoonScreen(tab.label)
    }
}

@Composable
private fun LearnHome(push: (Route) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ListItem(
            modifier = Modifier.clickable { push(Route.Dictionary) },
            headlineContent = { Text("Dictionary") },
            supportingContent = { Text("Offline JMdict · kanji · radicals · examples") },
        )
        ListItem(
            modifier = Modifier.clickable { push(Route.Radicals) },
            headlineContent = { Text("Radical search") },
            supportingContent = { Text("Find a kanji by its parts") },
        )
    }
}
