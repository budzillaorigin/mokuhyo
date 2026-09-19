package app.tsumugi.android.features.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.percent
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.readable
import app.tsumugi.study.TodayPlanner
import app.tsumugi.study.activities.Activity
import app.tsumugi.study.games.AtomGame
import app.tsumugi.study.games.AtomPuzzle
import app.tsumugi.study.games.AtomTapResult
import app.tsumugi.study.games.GameKind
import app.tsumugi.study.games.GameResult
import app.tsumugi.study.games.GameScoreRow
import app.tsumugi.study.games.ReflexCard
import app.tsumugi.study.games.ReflexGame
import app.tsumugi.study.games.ReflexOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/*
 * Mini-games (BRIEF_V2 §6.9, D-286/D-287, D-291). The shared ReflexGame/AtomGame own every rule: scoring, streaks,
 * time limits and deadlines. These screens only draw the current card or puzzle, report taps, and poll the clock so a
 * missed deadline turns into the game's own timeout. The same play views run standalone and inside the Pomodoro queue.
 */

private const val FEEDBACK_MS = 450L
private const val REVEAL_MS = 1_100L
private const val TICK_MS = 50L

private val RIGHT = Color(0xFF2E7D32)

/** Load state of a standalone game screen. */
private sealed interface GameLoad<out T> {
    data object Loading : GameLoad<Nothing>
    data object NoWords : GameLoad<Nothing>
    data class Failed(val message: String) : GameLoad<Nothing>
    data class Ready<T>(val game: T) : GameLoad<T>
}

/** Stores a finished round in `game_score` on the app scope (so leaving the screen doesn't drop it). */
private fun recordRound(context: android.content.Context, result: GameResult, onSaved: (String?, Throwable?) -> Unit) {
    val app = context.applicationContext as TsumugiApplication
    app.appScope.launch {
        runCatching { app.graph.games.record(result) }
            .onSuccess { onSaved(it, null) }
            .onFailure { onSaved(null, it) }
    }
}

// --- Hub -----------------------------------------------------------------------------------------------------------

private data class GameSummary(val reflexBest: Int, val atomBest: Int, val week: Int, val recent: List<GameScoreRow>)

/** Games hub: both games with their best score, this week's points toward the weekly challenge, recent rounds. */
@Composable
fun GamesScreen(onReflex: () -> Unit, onAtom: () -> Unit) {
    val graph = rememberGraph()
    var summary by remember { mutableStateOf<GameSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching {
            GameSummary(
                graph.games.best(GameKind.REFLEX), graph.games.best(GameKind.ATOM), graph.games.weekPoints(),
                (graph.games.recent(GameKind.REFLEX, 5) + graph.games.recent(GameKind.ATOM, 5)).sortedByDescending { it.playedAt }.take(8),
            )
        }.onSuccess { summary = it }.onFailure { error = it.readable() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.gm_intro), style = MaterialTheme.typography.bodyMedium)
        error?.let { ErrorState(stringResource(R.string.gm_load_failed, it), onRetry = { attempt++ }) }
        val s = summary
        if (s != null) {
            val goal = TodayPlanner.GAME_POINTS_GOAL
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (s.week >= goal) stringResource(R.string.gm_week_done, s.week) else stringResource(R.string.gm_week, s.week, goal),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LinearProgressIndicator(progress = { (s.week.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        } else if (error == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        GameCard(stringResource(R.string.title_reflex), stringResource(R.string.gm_reflex_sub), s?.reflexBest, onReflex)
        GameCard(stringResource(R.string.title_atom), stringResource(R.string.gm_atom_sub), s?.atomBest, onAtom)
        if (!s?.recent.isNullOrEmpty()) {
            SectionTitle(stringResource(R.string.gm_recent))
            s!!.recent.forEach { r ->
                ListItem(
                    headlineContent = { Text(stringResource(if (r.game == GameKind.REFLEX) R.string.title_reflex else R.string.title_atom)) },
                    supportingContent = { Text(stringResource(R.string.gm_round_row, r.score, r.correct, r.total, r.bestStreak, r.day)) },
                )
            }
        }
    }
}

@Composable
private fun GameCard(title: String, subtitle: String, best: Int?, onPlay: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (best != null) Text(stringResource(R.string.gm_best, best), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                else Row(Modifier.weight(1f)) {}
                Button(onClick = onPlay) { Text(stringResource(R.string.gm_play)) }
            }
        }
    }
}

// --- Standalone screens ----------------------------------------------------------------------------------------------

/** A standalone Reflex round (60 s); [key] gives each visit a fresh round. */
@Composable
fun ReflexScreen(key: String, onDone: () -> Unit) {
    val graph = rememberGraph()
    StandaloneGame(
        key = key,
        kind = GameKind.REFLEX,
        load = { graph.reflex() },
        onDone = onDone,
    ) { game, finish -> ReflexPlay(game, onFinished = finish) }
}

/** A standalone Atom round (90 s). */
@Composable
fun AtomScreen(key: String, onDone: () -> Unit) {
    val graph = rememberGraph()
    StandaloneGame(
        key = key,
        kind = GameKind.ATOM,
        load = { graph.atom() },
        onDone = onDone,
    ) { game, finish -> AtomPlay(game, onFinished = finish) }
}

@Composable
private fun <T : Any> StandaloneGame(
    key: String,
    kind: GameKind,
    load: suspend () -> T?,
    onDone: () -> Unit,
    play: @Composable (T, (GameResult) -> Unit) -> Unit,
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    var round by remember(key) { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<GameLoad<T>>(GameLoad.Loading) }
    var result by remember(key) { mutableStateOf<GameResult?>(null) }
    var previousBest by remember(key) { mutableStateOf<Int?>(null) }
    var saveMessage by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, round) {
        state = GameLoad.Loading
        result = null
        saveMessage = null
        previousBest = runCatching { graph.games.best(kind) }.getOrNull()
        state = runCatching { load() }.fold(
            onSuccess = { g -> if (g == null) GameLoad.NoWords else GameLoad.Ready(g) },
            onFailure = { GameLoad.Failed(it.readable()) },
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = state) {
            GameLoad.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            GameLoad.NoWords -> Notice(stringResource(R.string.gm_no_words))
            is GameLoad.Failed -> ErrorState(stringResource(R.string.gm_load_failed, s.message), onRetry = { round++ })
            is GameLoad.Ready -> {
                val r = result
                if (r == null) {
                    play(s.game) { finished ->
                        result = finished
                        if (finished.total == 0) {
                            saveMessage = context.getString(R.string.gm_not_saved)
                        } else {
                            recordRound(context, finished) { _, error ->
                                saveMessage = if (error == null) context.getString(R.string.gm_saved)
                                else context.getString(R.string.gm_save_failed, error.readable())
                            }
                        }
                    }
                } else {
                    GameResultCard(r, newBest = previousBest != null && r.score > previousBest!! && r.total > 0, saveMessage)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { round++ }) { Text(stringResource(R.string.gm_play_again)) }
                        OutlinedButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
                    }
                }
            }
        }
    }
}

/** The end-of-round summary (also shown in the Pomodoro queue). */
@Composable
fun GameResultCard(result: GameResult, newBest: Boolean = false, message: String? = null) {
    Card(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.gm_round_over), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.gm_result, result.score, result.correct, result.total, result.bestStreak) +
                    (result.accuracy?.let { " · ${percent(it)}" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            if (newBest) Text(stringResource(R.string.gm_new_best), color = RIGHT, style = MaterialTheme.typography.titleMedium)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * A game round inside the Pomodoro queue (`Activity.Reflex` / `Activity.Atom`, 45 s): plays [activity]'s game, stores
 * the round, shows the result, and [onNext] moves the queue on with it. The game is keyed on [activity], so the
 * session's once-a-second recomposition doesn't restart it.
 */
@Composable
fun QueuedGameRound(activity: Activity, onNext: (GameResult) -> Unit) {
    val context = LocalContext.current
    val game: Any? = remember(activity) {
        when (activity) {
            is Activity.Reflex -> activity.game()
            is Activity.Atom -> activity.game()
            else -> null
        }
    }
    var result by remember(activity) { mutableStateOf<GameResult?>(null) }
    var message by remember(activity) { mutableStateOf<String?>(null) }
    val r = result
    if (r == null) {
        val finish: (GameResult) -> Unit = { finished ->
            result = finished
            if (finished.total == 0) message = context.getString(R.string.gm_not_saved)
            else recordRound(context, finished) { _, error ->
                message = if (error == null) context.getString(R.string.gm_saved) else context.getString(R.string.gm_save_failed, error.readable())
            }
        }
        when (game) {
            is ReflexGame -> ReflexPlay(game, onFinished = finish)
            is AtomGame -> AtomPlay(game, onFinished = finish)
            else -> Notice(stringResource(R.string.gm_no_words))
        }
    } else {
        GameResultCard(r, message = message)
        Button(onClick = { onNext(r) }) { Text(stringResource(R.string.action_next)) }
    }
}

// --- Reflex --------------------------------------------------------------------------------------------------------

/** One Reflex round: word + meaning, "match" / "no match" before the card's deadline. */
@Composable
fun ReflexPlay(game: ReflexGame, onFinished: (GameResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var card by remember(game) { mutableStateOf<ReflexCard?>(game.next()) }
    var outcome by remember(game) { mutableStateOf<ReflexOutcome?>(null) }
    var busy by remember(game) { mutableStateOf(false) }
    var done by remember(game) { mutableStateOf(false) }
    var now by remember { mutableStateOf(Clock.System.now()) }

    fun finish() {
        if (done) return
        done = true
        card = null
        onFinished(game.result())
    }

    fun settle(result: ReflexOutcome?) {
        if (busy) return
        busy = true
        outcome = result
        card = null
        scope.launch {
            delay(FEEDBACK_MS)
            val next = game.next()
            busy = false
            if (next == null) finish() else card = next
        }
    }

    LaunchedEffect(game) {
        if (card == null) finish()
        while (!done) {
            delay(TICK_MS)
            now = Clock.System.now()
            val c = card
            if (c != null && !busy && now > c.deadline) settle(game.timeout())
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.gm_score, game.score), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.gm_streak, game.streak), style = MaterialTheme.typography.titleMedium)
    }
    Text(stringResource(R.string.gm_time_left, game.remaining.inWholeSeconds.toInt()), style = MaterialTheme.typography.labelLarge)
    LinearProgressIndicator(progress = { (game.remaining / game.roundLength).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())

    val c = card
    val shown = c ?: outcome?.card
    if (shown != null) {
        Text(stringResource(R.string.gm_reflex_q), style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                JaText(shown.word.text, style = MaterialTheme.typography.displaySmall)
                if (shown.word.reading != shown.word.text) JaText(shown.word.reading, style = MaterialTheme.typography.titleMedium)
                Text(shown.shownMeaning, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (c != null) {
            val left = ((c.deadline - now) / c.timeLimit).toFloat().coerceIn(0f, 1f)
            val cardTime = stringResource(R.string.gm_card_time)
            LinearProgressIndicator(progress = { left }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = cardTime })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { if (card != null) settle(game.answer(true)) },
                enabled = c != null,
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RIGHT),
            ) { Text(stringResource(R.string.gm_match), style = MaterialTheme.typography.titleMedium) }
            Button(
                onClick = { if (card != null) settle(game.answer(false)) },
                enabled = c != null,
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.gm_no_match), style = MaterialTheme.typography.titleMedium) }
        }
    }
    outcome?.takeIf { card == null && !done }?.let { o ->
        Text(
            when {
                o.timedOut -> stringResource(R.string.gm_timeout)
                o.correct -> stringResource(R.string.gm_right_points, o.points)
                else -> stringResource(R.string.gm_wrong)
            },
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (o.correct) RIGHT else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

// --- Atom ----------------------------------------------------------------------------------------------------------

/** One Atom round: tap the reading's morae in order from shuffled tiles before the puzzle's deadline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AtomPlay(game: AtomGame, onFinished: (GameResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var puzzle by remember(game) { mutableStateOf<AtomPuzzle?>(game.next()) }
    val used = remember(game) { mutableStateListOf<Int>() }
    var assembled by remember(game) { mutableStateOf<List<String>>(emptyList()) }
    var mistakes by remember(game) { mutableIntStateOf(0) }
    var wrongTile by remember(game) { mutableStateOf<Int?>(null) }
    /** After a puzzle ends: the solved points, or null with [reveal] showing the answer. */
    var solvedPoints by remember(game) { mutableStateOf<Int?>(null) }
    var reveal by remember(game) { mutableStateOf<String?>(null) }
    var busy by remember(game) { mutableStateOf(false) }
    var done by remember(game) { mutableStateOf(false) }
    var now by remember { mutableStateOf(Clock.System.now()) }

    fun finish() {
        if (done) return
        done = true
        puzzle = null
        onFinished(game.result())
    }

    fun advance(pause: Long) {
        busy = true
        scope.launch {
            delay(pause)
            val next = game.next()
            used.clear(); assembled = emptyList(); mistakes = 0; wrongTile = null; solvedPoints = null; reveal = null
            busy = false
            if (next == null) finish() else puzzle = next
        }
    }

    fun endPuzzle(p: AtomPuzzle) {
        if (busy) return
        game.giveUp()
        reveal = p.word.reading
        advance(REVEAL_MS)
    }

    LaunchedEffect(game) {
        if (puzzle == null) finish()
        while (!done) {
            delay(TICK_MS)
            now = Clock.System.now()
            val p = puzzle
            if (p != null && !busy && (now > p.deadline || game.remaining.inWholeMilliseconds == 0L)) endPuzzle(p)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.gm_score, game.score), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.gm_streak, game.streak), style = MaterialTheme.typography.titleMedium)
    }
    Text(stringResource(R.string.gm_time_left, game.remaining.inWholeSeconds.toInt()), style = MaterialTheme.typography.labelLarge)
    LinearProgressIndicator(progress = { (game.remaining / game.roundLength).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())

    val p = puzzle ?: return
    Text(stringResource(R.string.gm_atom_q), style = MaterialTheme.typography.bodyMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JaText(p.prompt, style = MaterialTheme.typography.titleLarge)
            val built = assembled.joinToString("")
            val builtLabel = stringResource(R.string.gm_assembled_desc, built)
            JaText(
                built + "＿".repeat((p.target.size - assembled.size).coerceAtLeast(0)),
                Modifier.semantics { contentDescription = builtLabel },
                style = MaterialTheme.typography.displaySmall,
            )
        }
    }
    if (!busy) {
        val left = ((p.deadline - now) / p.timeLimit).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { left }, modifier = Modifier.fillMaxWidth())
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        p.tiles.forEach { tile ->
            val placed = tile.id in used
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    val st = game.tap(tile.id) ?: return@OutlinedButton
                    assembled = st.assembled
                    mistakes = st.mistakes
                    when (st.result) {
                        AtomTapResult.PLACED -> { used += tile.id; wrongTile = null }
                        AtomTapResult.WRONG -> wrongTile = tile.id
                        AtomTapResult.SOLVED -> { used += tile.id; solvedPoints = st.points; advance(FEEDBACK_MS * 2) }
                        AtomTapResult.TIMED_OUT -> { reveal = p.word.reading; advance(REVEAL_MS) }
                    }
                },
                enabled = !placed && !busy,
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
                colors = if (wrongTile == tile.id) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                else ButtonDefaults.outlinedButtonColors(),
            ) { JaText(tile.kana, style = MaterialTheme.typography.headlineSmall) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                game.undo()?.let { st -> assembled = st.assembled; if (used.isNotEmpty()) used.removeAt(used.lastIndex) }
            },
            enabled = !busy && used.isNotEmpty(),
        ) { Text(stringResource(R.string.gm_undo)) }
        TextButton(onClick = { endPuzzle(p) }, enabled = !busy) { Text(stringResource(R.string.gm_give_up)) }
        if (mistakes > 0) Text(stringResource(R.string.gm_mistakes, mistakes), style = MaterialTheme.typography.labelMedium)
    }
    val feedback = solvedPoints?.let { stringResource(R.string.gm_solved, it) } ?: reveal?.let { stringResource(R.string.gm_answer_was, it) }
    feedback?.let {
        Text(
            it,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (solvedPoints != null) RIGHT else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
