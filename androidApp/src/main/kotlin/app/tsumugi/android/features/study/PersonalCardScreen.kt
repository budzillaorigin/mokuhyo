package app.tsumugi.android.features.study

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.RecordButton
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.features.practice.recordingLabel
import app.tsumugi.android.platform.AudioFilePlayer
import app.tsumugi.android.platform.storeRecording
import app.tsumugi.android.ui.Exports
import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.android.ui.rememberBitmap
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.UserImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import app.tsumugi.recordings.Recording as StoredRecording

/**
 * A personal (Fluent Forever) card (G-12, D-112): a picture from the system Photo Picker on the front, the word, an
 * optional note and meaning, and the learner's own recording. No photo permission is needed (the picker grants
 * access to the one image), and the image is copied into app storage.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonalCardScreen(onDone: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var word by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<UserImage?>(null) }
    var audio by remember { mutableStateOf<StoredRecording?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val imagePath = image?.let { graph.images.pathOf(it) }
    val bitmap = rememberBitmap(imagePath)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val ext = when (context.contentResolver.getType(uri)) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "jpg"
                }
                val pending = graph.images.newImage(ext)
                Exports.copy(context, uri, File(pending.path))
                image?.let { old -> graph.images.delete(old.id) }
                image = graph.images.register(pending)
                message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.readable()
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.personal_intro), style = MaterialTheme.typography.bodySmall)
        bitmap?.let {
            Image(it.asImageBitmap(), stringResource(R.string.personal_picture_description), Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit)
        }
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
            Text(stringResource(if (image == null) R.string.personal_pick_picture else R.string.personal_change_picture))
        }
        OutlinedTextField(word, { word = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.personal_word)) }, singleLine = true, textStyle = MaterialTheme.typography.titleLarge.japanese())
        OutlinedTextField(reading, { reading = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.personal_reading)) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.japanese())
        OutlinedTextField(meaning, { meaning = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.personal_meaning)) }, singleLine = true)
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.personal_note)) }, minLines = 2)
        Text(stringResource(R.string.personal_audio), style = MaterialTheme.typography.titleSmall)
        audio?.let { rec ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(recordingLabel(rec), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { AudioFilePlayer.play(graph.recordings.pathOf(rec)) } }) { PlayLabel(stringResource(R.string.rec_play_mine)) }
            }
        }
        RecordButton(
            onRecorded = { pcm ->
                scope.launch {
                    runCatching {
                        audio?.let { graph.recordings.delete(it.id) }
                        graph.storeRecording(pcm, RecordingKind.CARD, null)
                    }.onSuccess { audio = it }.onFailure { message = it.readable() }
                }
            },
            onError = { message = it.ifBlank { context.getString(R.string.rec_too_short) } },
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        graph.personalCards.create(
                            word, reading.ifBlank { null }, meaning.ifBlank { null }, note,
                            imageId = image?.id, audioRecordingId = audio?.id,
                        )
                        onDone()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = e.readable()
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = word.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.personal_save)) }
        Text(stringResource(R.string.personal_save_hint), style = MaterialTheme.typography.bodySmall)
    }
}
