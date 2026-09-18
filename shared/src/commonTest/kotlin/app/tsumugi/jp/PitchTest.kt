package app.tsumugi.jp

import kotlin.test.Test
import kotlin.test.assertEquals

class PitchTest {

    private val H = true
    private val L = false

    @Test
    fun heibanRisesAndStaysHigh() {
        val a = Pitch.accent("さくら", 0)
        assertEquals(PitchPattern.HEIBAN, a.pattern)
        assertEquals(listOf(L, H, H, H), a.heights())
    }

    @Test
    fun atamadakaStartsHigh() {
        val a = Pitch.accent("いのち", 1)
        assertEquals(PitchPattern.ATAMADAKA, a.pattern)
        assertEquals(listOf(H, L, L, L), a.heights())
    }

    @Test
    fun nakadakaDropsInside() {
        val a = Pitch.accent("おかし", 2)
        assertEquals(PitchPattern.NAKADAKA, a.pattern)
        assertEquals(listOf(L, H, L, L), a.heights())

        val b = Pitch.accent("せんせい", 3) // hypothetical 3-drop on a 4-mora word
        assertEquals(PitchPattern.NAKADAKA, b.pattern)
        assertEquals(listOf(L, H, H, L, L), b.heights())
    }

    @Test
    fun odakaDropsOnParticle() {
        val a = Pitch.accent("はな", 2) // 花
        assertEquals(PitchPattern.ODAKA, a.pattern)
        assertEquals(listOf(L, H, L), a.heights())
    }

    @Test
    fun heibanVsOdakaDifferOnlyInParticle() {
        val hana1 = Pitch.accent("はな", 0).heights() // 鼻
        val hana2 = Pitch.accent("はな", 2).heights() // 花
        assertEquals(hana1.dropLast(1), hana2.dropLast(1))
        assertEquals(H, hana1.last())
        assertEquals(L, hana2.last())
    }

    @Test
    fun singleMoraWords() {
        assertEquals(PitchPattern.ATAMADAKA, Pitch.accent("き", 1).pattern)
        assertEquals(listOf(H, L), Pitch.accent("き", 1).heights())
        assertEquals(PitchPattern.HEIBAN, Pitch.accent("き", 0).pattern)
        assertEquals(listOf(L, H), Pitch.accent("き", 0).heights())
    }

    @Test
    fun countsMoraeNotCharacters() {
        val a = Pitch.accent("きょう", 1)
        assertEquals(2, a.moraCount)
        assertEquals(listOf(H, L, L), a.heights())
        assertEquals(4, Pitch.accent("がっこう", 0).moraCount)
        assertEquals(5, Pitch.accent("がっこう", 0).heights().size)
    }

    @Test
    fun parsesAccentLists() {
        assertEquals(listOf(0, 2), Pitch.parse("0,2"))
        assertEquals(listOf(1), Pitch.parse(" 1 "))
        assertEquals(listOf(3, 0), Pitch.parse("3, 0"))
        assertEquals(emptyList(), Pitch.parse(""))
        assertEquals(listOf(4), Pitch.parse("x,4,"))
    }
}
