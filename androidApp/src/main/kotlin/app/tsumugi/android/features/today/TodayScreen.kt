package app.tsumugi.android.features.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tsumugi.api.SharedGraph

@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    val useCase = remember { SharedGraph.helloUseCase() }
    val greeting by useCase.greeting().collectAsState(initial = null)

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        greeting?.let {
            Text(it.title, style = MaterialTheme.typography.displayMedium)
            Text(it.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
