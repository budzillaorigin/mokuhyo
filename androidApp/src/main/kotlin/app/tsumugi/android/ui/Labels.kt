package app.tsumugi.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage
import app.tsumugi.srs.Rating
import app.tsumugi.study.LearningGoal
import app.tsumugi.study.LearningPhase

/*
 * Localized names for shared-core enums. The shared module keeps English labels (used by exports and the iOS app);
 * the Android UI maps them to string resources here.
 */

@Composable
fun ItemKind.localized(): String = stringResource(
    when (this) {
        ItemKind.RADICAL -> R.string.kind_radical
        ItemKind.KANJI -> R.string.kind_kanji
        ItemKind.VOCAB -> R.string.kind_vocab
        ItemKind.GRAMMAR -> R.string.kind_grammar
        ItemKind.SENTENCE -> R.string.kind_sentence
        ItemKind.LISTENING -> R.string.kind_listening
        ItemKind.WRITING -> R.string.kind_writing
        ItemKind.MINIMAL_PAIR -> R.string.kind_minimal_pair
        ItemKind.CUSTOM -> R.string.kind_custom
    },
)

@Composable
fun Stage.localized(): String = stringResource(
    when (this) {
        Stage.APPRENTICE -> R.string.stage_apprentice
        Stage.GURU -> R.string.stage_guru
        Stage.MASTER -> R.string.stage_master
        Stage.ENLIGHTENED -> R.string.stage_enlightened
        Stage.BURNED -> R.string.stage_burned
    },
)

@Composable
fun Rating.localized(): String = stringResource(
    when (this) {
        Rating.AGAIN -> R.string.rating_again
        Rating.HARD -> R.string.rating_hard
        Rating.GOOD -> R.string.rating_good
        Rating.EASY -> R.string.rating_easy
    },
)

@Composable
fun LearningGoal.localized(): String = stringResource(
    when (this) {
        LearningGoal.JLPT -> R.string.goal_jlpt
        LearningGoal.DLPT -> R.string.goal_dlpt
        LearningGoal.GENERAL -> R.string.goal_general
    },
)

@Composable
fun LearningPhase.localized(): String = stringResource(
    when (this) {
        LearningPhase.FOUNDATIONS -> R.string.phase_foundations
        LearningPhase.CORE -> R.string.phase_core
        LearningPhase.INTERMEDIATE -> R.string.phase_intermediate
        LearningPhase.ADVANCED -> R.string.phase_advanced
    },
)
