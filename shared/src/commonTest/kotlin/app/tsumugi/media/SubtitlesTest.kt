package app.tsumugi.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitlesTest {

    private val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>こんにちは</i>\r\n\r\n2\r\n00:00:04,000 --> 00:00:06,000\r\n{\\an8}元気ですか。\r\nはい。\r\n\r\n"

    private val vtt = """
        WEBVTT

        NOTE a comment

        00:01.200 --> 00:03.400 align:start
        Hello

        intro
        00:00:04.100 --> 00:00:05.900
        <c.yellow>How are you?</c>
    """.trimIndent()

    @Test
    fun parsesSrt() {
        val cues = Subtitles.parse(srt)
        assertEquals(listOf(Cue(1000, 3500, "こんにちは"), Cue(4000, 6000, "元気ですか。\nはい。")), cues)
    }

    @Test
    fun parsesVtt() {
        val cues = Subtitles.parse(vtt)
        assertEquals(listOf(Cue(1200, 3400, "Hello"), Cue(4100, 5900, "How are you?")), cues)
    }

    @Test
    fun cueAtPosition() {
        val cues = Subtitles.parse(srt)
        assertEquals("こんにちは", Subtitles.cueAt(cues, 1000)?.text)
        assertNull(Subtitles.cueAt(cues, 3700))
        assertEquals(1, Subtitles.indexAt(cues, 5000))
        assertNull(Subtitles.cueAt(cues, 500))
    }

    @Test
    fun dualPairsByOverlap() {
        val dual = Subtitles.dual(Subtitles.parse(srt), Subtitles.parse(vtt))
        assertEquals("Hello", dual[0].english)
        assertEquals("How are you?", dual[1].english)
    }
}
