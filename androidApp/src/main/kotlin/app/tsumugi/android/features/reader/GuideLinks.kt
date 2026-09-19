package app.tsumugi.android.features.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.reader.GuidesLibrary

/**
 * The guides library for a grammar point (BRIEF_V2 §6.4, D-166): free explanations elsewhere, link-only. Nothing is
 * fetched or shown in the app; a tap opens the system browser. Hidden when no guide covers the point.
 */
@Composable
fun GuideLinks(pointId: String) {
    val context = LocalContext.current
    val links = remember(pointId) { GuidesLibrary.forGrammarPoint(pointId) }
    var failed by remember { mutableStateOf(false) }
    if (links.isEmpty()) return
    Column {
        Text(stringResource(R.string.guides_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        links.take(8).forEach { g ->
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                    failed = try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(g.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } catch (_: ActivityNotFoundException) {
                        true
                    }
                }.padding(vertical = 6.dp),
            ) {
                Text(g.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(g.site, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(stringResource(if (failed) R.string.guides_no_browser else R.string.guides_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}
