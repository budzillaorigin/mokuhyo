package app.tsumugi.android.features.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tsumugi.android.ui.japanese

/** Placeholder until the Today planner lands (Phase 3). */
@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("今日", style = MaterialTheme.typography.displayMedium.japanese())
        Text("Your daily plan appears here once lessons and reviews are set up.", style = MaterialTheme.typography.bodyMedium)
    }
}
