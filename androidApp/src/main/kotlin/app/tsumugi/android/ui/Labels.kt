package app.tsumugi.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage
import app.tsumugi.l10n.Labels
import app.tsumugi.srs.Rating
import app.tsumugi.study.LearningGoal
import app.tsumugi.study.LearningPhase

/*
 * Localized names for shared-core enums (G-14, D-109). Stages, item kinds, ratings and phases come from the shared
 * string table (`app.tsumugi.l10n.Labels`, set to the UI language by `L10n.setLanguage` at startup and on every
 * language change), so both apps say the same thing. Only labels the table doesn't have stay in Android resources.
 */

fun ItemKind.localized(): String = Labels.kind(this)

fun Stage.localized(): String = Labels.stage(this)

fun Rating.localized(): String = Labels.rating(this)

fun LearningPhase.localized(): String = Labels.phase(this)

/** Onboarding goals are Android UI chrome (not in the shared table). */
@Composable
fun LearningGoal.localized(): String = stringResource(
    when (this) {
        LearningGoal.JLPT -> R.string.goal_jlpt
        LearningGoal.DLPT -> R.string.goal_dlpt
        LearningGoal.GENERAL -> R.string.goal_general
    },
)
