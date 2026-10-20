package app.mokuhyo.ai.jni

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class Utf8ChunksTest {
    private val samples = mapOf(
        "ja" to "ご出身はどちらですか。", "zh-Hans" to "你在哪儿工作？", "ko" to "어디에서 일하세요?",
        "ar" to "أين تعمل الآن؟", "fa" to "کجا کار می‌کنید؟", "ru" to "Где вы работаете?", "emoji" to "Bien 😀 ok",
    )

    @Test
    fun multiByteCharactersSplitAtEveryByteComeOutIntact() {
        samples.forEach { (lang, text) ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            for (cut in 1 until bytes.size) {
                val u = Utf8Chunks()
                val out = u.append(bytes.copyOfRange(0, cut)) + u.append(bytes.copyOfRange(cut, bytes.size)) + u.finish()
                assertEquals(text, out, "$lang split at byte $cut")
                assertFalse('�' in out)
            }
            // One byte at a time, like the worst tokenizer.
            val u = Utf8Chunks()
            assertEquals(text, bytes.joinToString("") { u.append(byteArrayOf(it)) } + u.finish(), lang)
        }
    }
}
