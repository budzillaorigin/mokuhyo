package app.mokuhyo.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** Whisper segments are joined with a space in spaced languages (they were glued together: "للطلابالتعليم"). */
class TranscriptJoinTest {
    private fun seg(t: String) = TranscriptSegment(0, 0, t)

    @Test
    fun spacedLanguagesGetASpaceBetweenSegments() {
        assertEquals("الجديدة للطلاب. التعليم الأفضل", WhisperRecognizer.joinSegments(listOf(seg(" الجديدة للطلاب."), seg("التعليم الأفضل ")), "ar"))
        assertEquals("Vivo en Madrid. El año pasado…", WhisperRecognizer.joinSegments(listOf(seg("Vivo en Madrid."), seg(" El año pasado…")), "es"))
        assertEquals("오늘 아침 드론을 봤습니다", WhisperRecognizer.joinSegments(listOf(seg("오늘 아침"), seg("드론을 봤습니다")), "ko"))
        assertEquals("今朝ドローンを見ました。北門です。", WhisperRecognizer.joinSegments(listOf(seg("今朝ドローンを見ました。"), seg("北門です。")), "ja"))
        assertEquals("今天早上看到了无人机。", WhisperRecognizer.joinSegments(listOf(seg("今天早上"), seg(""), seg("看到了无人机。")), "zh"))
    }
}
