package app.tsumugi.android.platform

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One recognized line with its bounds (image pixels), so the UI can let the user tap a line to look it up. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * On-device Japanese OCR with Google ML Kit Text Recognition v2 (bundled model: free, no API key, works offline;
 * see docs/LICENSES.md for its terms).
 */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }

    suspend fun recognize(context: Context, image: Uri): List<OcrLine> {
        val input = InputImage.fromFilePath(context, image)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    cont.resume(result.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            val b = line.boundingBox
                            OcrLine(line.text, b?.left ?: 0, b?.top ?: 0, b?.right ?: 0, b?.bottom ?: 0)
                        }
                    })
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}
