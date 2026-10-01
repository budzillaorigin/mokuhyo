package app.mokuhyo.platform

import okio.Path
import java.io.File
import java.text.Normalizer

actual fun freeBytes(path: Path): Long? {
    var f: File? = path.toFile()
    while (f != null && !f.exists()) f = f.parentFile
    return f?.usableSpace
}

actual fun normalizeNfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

actual fun normalizeNfd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
