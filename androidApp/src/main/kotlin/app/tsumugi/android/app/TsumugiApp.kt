package app.tsumugi.android.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.features.exams.displayTitle
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.OnboardingScreen
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.features.exams.AttemptReviewScreen
import app.tsumugi.android.features.exams.ExamHubScreen
import app.tsumugi.android.features.exams.ExamRunScreen
import app.tsumugi.android.features.me.AiSettingsScreen
import app.tsumugi.android.features.practice.DialogueListScreen
import app.tsumugi.android.features.practice.DialoguePlayerScreen
import app.tsumugi.android.features.practice.MediaPlayerScreen
import app.tsumugi.android.features.practice.MinimalPairsScreen
import app.tsumugi.android.features.practice.OpiScreen
import app.tsumugi.android.features.practice.PomodoroScreen
import app.tsumugi.android.features.practice.PracticeHubScreen
import app.tsumugi.android.features.practice.RoleplayScreen
import app.tsumugi.android.features.practice.ScenarioListScreen
import app.tsumugi.android.features.dictionary.DictionaryNav
import app.tsumugi.android.features.dictionary.DictionarySearchScreen
import app.tsumugi.android.features.dictionary.EntryScreen
import app.tsumugi.android.features.dictionary.KanjiScreen
import app.tsumugi.android.features.dictionary.RadicalSearchScreen
import app.tsumugi.android.features.dictionary.WordListScreen
import app.tsumugi.android.features.dictionary.WordListsScreen
import app.tsumugi.android.features.me.ImportScreen
import app.tsumugi.android.features.reader.AozoraScreen
import app.tsumugi.android.features.reader.FeedsScreen
import app.tsumugi.android.features.reader.ReaderLibraryScreen
import app.tsumugi.android.features.reader.ReaderScreen
import app.tsumugi.android.features.reader.ScanScreen
import app.tsumugi.android.features.writing.HandwritingSearchScreen
import app.tsumugi.android.features.writing.WritingPracticeScreen
import app.tsumugi.android.features.me.LicensesScreen
import app.tsumugi.android.features.me.MeDestination
import app.tsumugi.android.features.me.MeScreen
import app.tsumugi.android.features.me.SettingsScreen
import app.tsumugi.android.features.me.SyncScreen
import app.tsumugi.android.features.study.GrammarLessonScreen
import app.tsumugi.android.features.study.GrammarLevelScreen
import app.tsumugi.android.features.study.GrammarLevelsScreen
import app.tsumugi.android.features.study.GrammarPointScreen
import app.tsumugi.android.features.study.LessonScreen
import app.tsumugi.android.features.study.PathItemScreen
import app.tsumugi.android.features.study.PathLevelScreen
import app.tsumugi.android.features.study.PathLevelsScreen
import app.tsumugi.android.features.study.ReviewScreen
import app.tsumugi.android.features.today.TodayScreen
import app.tsumugi.android.ui.TsumugiTheme

/** Text handed to the app by another app: the share sheet ("Read in Tsumugi") or the text-selection menu ("Look up"). */
sealed interface Incoming {
    data class Read(val text: String) : Incoming
    data class Lookup(val text: String) : Incoming
}

private val URL = Regex("https?://\\S+")
private val JAPANESE = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uf900-\\ufaff\\uff66-\\uff9f]")

/** A shared web page arrives as its URL, sometimes with a title around it; anything with Japanese in it is read as text. */
private fun sharedUrl(text: String): String? {
    val trimmed = text.trim()
    if (URL.matchEntire(trimmed) != null) return trimmed
    return if (JAPANESE.containsMatchIn(trimmed)) null else URL.find(trimmed)?.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TsumugiApp(incoming: Incoming? = null, onIncomingHandled: () -> Unit = {}) {
    val nav: NavigationViewModel = viewModel()
    val dictionaryNav = DictionaryNav(
        openEntry = { nav.push(Route.Entry(it)) },
        openKanji = { nav.push(Route.Kanji(it)) },
        openRadicals = { nav.push(Route.Radicals) },
    )
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    var onboarded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { onboarded = graph.onboarding.isDone() }
    val context = LocalContext.current
    var shareBusy by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    // Shared text opens once onboarding is done: a URL is fetched into the reader, other text is imported as is.
    LaunchedEffect(incoming, onboarded) {
        val item = incoming ?: return@LaunchedEffect
        if (onboarded != true) return@LaunchedEffect
        onIncomingHandled()
        when (item) {
            is Incoming.Lookup -> nav.open(Tab.LEARN, Route.Lookup(item.text.trim()))
            is Incoming.Read -> {
                shareBusy = true
                runCatching {
                    val url = sharedUrl(item.text)
                    if (url != null) graph.reader.importUrl(url) else graph.reader.importText(item.text)
                }.onSuccess { nav.open(Tab.LEARN, Route.Read(it)) }
                    .onFailure { shareError = context.getString(R.string.share_failed, it.message ?: it::class.simpleName.orEmpty()) }
                shareBusy = false
            }
        }
    }

    if (onboarded == false) {
        TsumugiTheme {
            Scaffold { padding ->
                Box(Modifier.padding(padding)) {
                    OnboardingScreen { openImport ->
                        onboarded = true
                        if (openImport) {
                            nav.select(Tab.ME)
                            nav.push(Route.Import)
                        }
                    }
                }
            }
        }
        return
    }

    // Strict exam modes allow no lookups: hide global search and the tab bar until the attempt ends.
    val lockedDown = (nav.current as? Route.ExamRun)?.spec?.strict == true

    TsumugiTheme {
        shareError?.let { message ->
            AlertDialog(
                onDismissRequest = { shareError = null },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { shareError = null }) { Text(stringResource(R.string.action_ok)) } },
            )
        }
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(routeTitle(nav.current, nav.tab), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        // In a strict exam, leaving goes through the runner's confirmation (system back) instead.
                        navigationIcon = {
                            if (nav.canGoBack && !lockedDown) {
                                val backLabel = stringResource(R.string.nav_back_description)
                                TextButton(onClick = nav::back, Modifier.semantics { contentDescription = backLabel }) {
                                    Text(stringResource(R.string.nav_back))
                                }
                            }
                        },
                        actions = {
                            if (nav.current != Route.Dictionary && !lockedDown) {
                                TextButton(onClick = nav::openSearch) { Text(stringResource(R.string.action_search)) }
                            }
                        },
                    )
                    if (shareBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            bottomBar = {
                if (!lockedDown) NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == nav.tab,
                            onClick = { nav.select(tab) },
                            // The glyph is decorative; TalkBack reads the label.
                            icon = { Text(tab.glyph, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleMedium.japanese()) },
                            label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (val route = nav.current) {
                    Route.TabRoot -> TabRoot(nav.tab, nav::push)
                    Route.Dictionary -> DictionarySearchScreen(dictionaryNav)
                    is Route.Lookup -> DictionarySearchScreen(dictionaryNav, route.query)
                    Route.Scan -> ScanScreen(onLookup = { nav.push(Route.Lookup(it)) })
                    Route.Library -> ReaderLibraryScreen(onOpen = { nav.push(Route.Read(it)) }, onFeeds = { nav.push(Route.Feeds) }, onAozora = { nav.push(Route.Aozora) })
                    is Route.Read -> ReaderScreen(route.docId, onOpenEntry = { nav.push(Route.Entry(it)) }, onOpenGrammar = { nav.push(Route.GrammarPoint(it)) })
                    Route.Feeds -> FeedsScreen(onOpenDoc = { nav.push(Route.Read(it)) })
                    Route.Aozora -> AozoraScreen(onOpenDoc = { nav.push(Route.Read(it)) })
                    is Route.WritingPractice -> WritingPracticeScreen(route.kanji, onDone = nav::back)
                    Route.Handwriting -> HandwritingSearchScreen(onPick = { nav.push(Route.Kanji(it)) })
                    is Route.Entry -> EntryScreen(route.id, dictionaryNav)
                    is Route.Kanji -> KanjiScreen(route.literal, dictionaryNav)
                    Route.Radicals -> RadicalSearchScreen(dictionaryNav)
                    Route.Lessons -> LessonScreen(onDone = nav::back, onOpenItem = { nav.push(Route.PathItem(it)) })
                    Route.Reviews -> ReviewScreen(onDone = nav::back)
                    Route.PathLevels -> PathLevelsScreen(onOpenLevel = { nav.push(Route.PathLevel(it)) })
                    is Route.PathLevel -> PathLevelScreen(route.level, onOpenItem = { nav.push(Route.PathItem(it)) })
                    is Route.PathItem -> PathItemScreen(route.id, onOpenItem = { nav.push(Route.PathItem(it)) })
                    Route.Settings -> SettingsScreen(onOpenAi = { nav.push(Route.AiSettings) })
                    Route.WordLists -> WordListsScreen(onOpen = { nav.push(Route.WordList(it)) })
                    Route.Grammar -> GrammarLevelsScreen(onOpenLevel = { nav.push(Route.GrammarLevel(it)) }, onLessons = { nav.push(Route.GrammarLessons) })
                    is Route.GrammarLevel -> GrammarLevelScreen(route.level, onOpenPoint = { nav.push(Route.GrammarPoint(it)) })
                    is Route.GrammarPoint -> GrammarPointScreen(route.id)
                    Route.GrammarLessons -> GrammarLessonScreen(onDone = nav::back)
                    is Route.WordList -> WordListScreen(route.id, onOpenEntry = { nav.push(Route.Entry(it)) })
                    Route.Import -> ImportScreen()
                    Route.Sync -> SyncScreen()
                    Route.Licenses -> LicensesScreen()
                    Route.AiSettings -> AiSettingsScreen()
                    Route.Scenarios -> ScenarioListScreen(onOpen = { nav.push(Route.Roleplay(it)) })
                    is Route.Roleplay -> RoleplayScreen(route.scenarioId, route.toString(), onOpenAiSettings = { nav.push(Route.AiSettings) })
                    Route.Dialogues -> DialogueListScreen(onOpen = { nav.push(Route.DialoguePlayer(it)) })
                    is Route.DialoguePlayer -> DialoguePlayerScreen(route.id)
                    Route.MinimalPairs -> MinimalPairsScreen()
                    Route.Media -> MediaPlayerScreen(onLookup = { nav.push(Route.Lookup(it)) })
                    is Route.Pomodoro -> PomodoroScreen(route.toString())
                    is Route.Opi -> OpiScreen(route.toString(), onOpenAiSettings = { nav.push(Route.AiSettings) })
                    Route.Exams -> ExamHubScreen(
                        onStart = { nav.push(Route.ExamRun(it)) },
                        onOpi = { nav.push(Route.Opi()) },
                        onOpenAttempt = { nav.push(Route.Attempt(it)) },
                        onImport = { nav.push(Route.Import) },
                    )
                    is Route.ExamRun -> ExamRunScreen(
                        route.spec, route.toString(),
                        onReview = { id -> nav.back(); nav.push(Route.Attempt(id)) },
                        onExit = nav::back,
                    )
                    is Route.Attempt -> AttemptReviewScreen(route.id, onOpenAiSettings = { nav.push(Route.AiSettings) })
                }
            }
        }
    }
}

@Composable
private fun TabRoot(tab: Tab, push: (Route) -> Unit) {
    when (tab) {
        Tab.TODAY -> TodayScreen(onLessons = { push(Route.Lessons) }, onReviews = { push(Route.Reviews) }, onGrammar = { push(Route.GrammarLessons) })
        Tab.REVIEWS -> TodayScreen(onLessons = { push(Route.Lessons) }, onReviews = { push(Route.Reviews) }, onGrammar = { push(Route.GrammarLessons) })
        Tab.LEARN -> LearnHome(push)
        Tab.PRACTICE -> PracticeHubScreen(push)
        Tab.ME -> MeScreen { d ->
            push(
                when (d) {
                    MeDestination.IMPORT -> Route.Import
                    MeDestination.SYNC -> Route.Sync
                    MeDestination.SETTINGS -> Route.Settings
                    MeDestination.LICENSES -> Route.Licenses
                    MeDestination.AI -> Route.AiSettings
                    MeDestination.EXAMS -> Route.Exams
                },
            )
        }
    }
}

@Composable
private fun LearnHome(push: (Route) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        listOf(
            Triple(R.string.title_kanji_path, R.string.learn_path_sub, Route.PathLevels),
            Triple(R.string.title_grammar, R.string.learn_grammar_sub, Route.Grammar),
            Triple(R.string.title_reading, R.string.learn_reading_sub, Route.Library),
            Triple(R.string.title_dictionary, R.string.learn_dictionary_sub, Route.Dictionary),
            Triple(R.string.title_radicals, R.string.learn_radicals_sub, Route.Radicals),
            Triple(R.string.title_draw_search, R.string.learn_draw_sub, Route.Handwriting),
            Triple(R.string.title_word_lists, R.string.learn_lists_sub, Route.WordLists),
            Triple(R.string.title_scan, R.string.learn_scan_sub, Route.Scan),
        ).forEach { (title, subtitle, route) ->
            ListItem(
                modifier = Modifier.clickable { push(route) },
                headlineContent = { Text(stringResource(title)) },
                supportingContent = { Text(stringResource(subtitle)) },
            )
        }
    }
}

/** Top-bar title for a screen; a tab's root shows the tab name. */
@Composable
private fun routeTitle(route: Route, tab: Tab): String = when (route) {
    Route.TabRoot -> stringResource(tab.label)
    Route.Dictionary, is Route.Lookup -> stringResource(R.string.title_dictionary)
    Route.Scan -> stringResource(R.string.title_scan)
    Route.Library -> stringResource(R.string.title_reading)
    is Route.Read -> stringResource(R.string.title_reader)
    Route.Feeds -> stringResource(R.string.title_feeds)
    Route.Aozora -> stringResource(R.string.title_aozora)
    is Route.WritingPractice -> stringResource(R.string.title_writing)
    Route.Handwriting -> stringResource(R.string.title_draw_search)
    is Route.Entry -> stringResource(R.string.title_word)
    is Route.Kanji -> route.literal
    Route.Radicals -> stringResource(R.string.title_radicals)
    Route.Lessons -> stringResource(R.string.title_lessons)
    Route.Reviews -> stringResource(R.string.title_reviews)
    Route.PathLevels -> stringResource(R.string.title_kanji_path)
    is Route.PathLevel -> stringResource(R.string.title_level, route.level)
    is Route.PathItem -> stringResource(R.string.title_item)
    Route.Settings -> stringResource(R.string.title_settings)
    Route.WordLists -> stringResource(R.string.title_word_lists)
    Route.Grammar -> stringResource(R.string.title_grammar)
    is Route.GrammarLevel -> stringResource(R.string.title_grammar_level, route.level)
    is Route.GrammarPoint -> stringResource(R.string.title_grammar)
    Route.GrammarLessons -> stringResource(R.string.title_grammar_lessons)
    is Route.WordList -> stringResource(R.string.title_word_list)
    Route.Import -> stringResource(R.string.title_import)
    Route.Sync -> stringResource(R.string.title_sync)
    Route.Licenses -> stringResource(R.string.title_licenses)
    Route.AiSettings -> stringResource(R.string.title_ai)
    Route.Scenarios, is Route.Roleplay -> stringResource(R.string.title_roleplay)
    Route.Dialogues -> stringResource(R.string.title_dialogues)
    is Route.DialoguePlayer -> stringResource(R.string.title_dialogue)
    Route.MinimalPairs -> stringResource(R.string.title_minimal_pairs)
    Route.Media -> stringResource(R.string.title_media)
    is Route.Pomodoro -> stringResource(R.string.title_speaking_session)
    is Route.Opi -> stringResource(R.string.title_opi)
    Route.Exams -> stringResource(R.string.title_exams)
    is Route.ExamRun -> route.spec.displayTitle()
    is Route.Attempt -> stringResource(R.string.title_attempt)
}
