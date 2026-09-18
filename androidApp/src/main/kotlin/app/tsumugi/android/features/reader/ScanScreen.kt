package app.tsumugi.android.features.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tsumugi.android.platform.Ocr
import app.tsumugi.android.ui.japanese
import kotlinx.coroutines.launch

/** Photo OCR (BRIEF §5.3/§5.8): pick an image, read it on device, tap a line to look it up. */
@Composable
fun ScanScreen(onLookup: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        status = "Reading the photo…"
        scope.launch {
            lines = runCatching { Ocr.recognize(context, uri).map { it.text } }.getOrElse { emptyList() }
            status = if (lines.isEmpty()) "No Japanese text found." else null
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose a photo") }
        Text("Text is recognized on your device. Tap a line to look it up.", style = MaterialTheme.typography.bodySmall)
        status?.let { Text(it) }
        LazyColumn {
            items(lines) { line ->
                ListItem(
                    modifier = Modifier.clickable { onLookup(line) },
                    headlineContent = { Text(line, style = MaterialTheme.typography.titleMedium.japanese()) },
                )
            }
        }
    }
}
