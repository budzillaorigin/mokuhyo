package app.tsumugi.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Export files handed to the share sheet (G-10, G-16). They live in cache/exports, served by the FileProvider. */
object Exports {
    fun dir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** Opens the share sheet for [file]; returns false when no app can take it. */
    fun share(context: Context, file: File, mime: String, title: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Writes [text] to a content URI picked with the system file picker (SAF). */
    suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("can't write the file")
    }

    /** Copies a picked content URI into [target]. */
    suspend fun copy(context: Context, uri: Uri, target: File) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("can't open the file")
    }
}

/** Decodes an image file off the main thread, downsampled to about [maxPx] on the long side. */
@Composable
fun rememberBitmap(path: String?, maxPx: Int = 1080): Bitmap? {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = path?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(p, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                    BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
        }
    }
    return bitmap
}
