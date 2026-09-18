package app.tsumugi.android.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.tsumugi.android.features.ComingSoonScreen
import app.tsumugi.android.features.today.TodayScreen

enum class Tab(val label: String, val glyph: String) {
    TODAY("Today", "今"),
    REVIEWS("Reviews", "復"),
    LEARN("Learn", "学"),
    PRACTICE("Practice", "練"),
    ME("Me", "私"),
}

@Composable
fun TsumugiApp() {
    var selected by rememberSaveable { mutableStateOf(Tab.TODAY) }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selected,
                            onClick = { selected = tab },
                            icon = { Text(tab.glyph, style = MaterialTheme.typography.titleMedium) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (selected) {
                Tab.TODAY -> TodayScreen(modifier)
                else -> ComingSoonScreen(selected.label, modifier)
            }
        }
    }
}

@Preview
@Composable
private fun TsumugiAppPreview() = TsumugiApp()
