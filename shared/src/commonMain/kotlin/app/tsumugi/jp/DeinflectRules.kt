package app.tsumugi.jp

/**
 * Grammatical state a (partially) deinflected string can be in. Dictionary classes mirror [WordClass];
 * the rest are intermediate states that only exist mid-chain.
 */
internal enum class Cond {
    V1, V5, VK, VS, ADJ_I, ADJ_NA,

    /** A て/で form waiting to be resolved to its verb (e.g. after peeling 〜ている). */
    TE,

    /** A finished, non-inflecting surface form (after peeling です). */
    PLAIN,
    ;

    val wordClass: WordClass?
        get() = when (this) {
            V1 -> WordClass.ICHIDAN
            V5 -> WordClass.GODAN
            VK -> WordClass.KURU
            VS -> WordClass.SURU
            ADJ_I -> WordClass.ADJ_I
            ADJ_NA -> WordClass.ADJ_NA
            TE, PLAIN -> null
        }
}

/**
 * One suffix rewrite: a string ending in [from] whose state is in [condIn] becomes one ending in [to] in state
 * [condOut]. The raw input matches every [condIn]. [exact] rules only fire when the whole string equals [from].
 */
internal data class Rule(
    val from: String,
    val to: String,
    val condIn: Set<Cond>,
    val condOut: Set<Cond>,
    val reason: String,
    val exact: Boolean = false,
)

/**
 * The deinflection table. Written from first principles of Japanese conjugation (verb stems by godan row,
 * て/た euphony, auxiliaries that themselves inflect as ichidan/godan/い-adjective).
 */
internal object DeinflectRules {

    private val V = setOf(Cond.V1, Cond.V5, Cond.VK, Cond.VS)
    private val PLAIN = setOf(Cond.PLAIN)

    /** Godan dictionary endings and their a/i/e/o-row stems. */
    private data class GodanRow(val u: Char, val a: Char, val i: Char, val e: Char, val o: Char)

    private val godanRows = listOf(
        GodanRow('う', 'わ', 'い', 'え', 'お'),
        GodanRow('く', 'か', 'き', 'け', 'こ'),
        GodanRow('ぐ', 'が', 'ぎ', 'げ', 'ご'),
        GodanRow('す', 'さ', 'し', 'せ', 'そ'),
        GodanRow('つ', 'た', 'ち', 'て', 'と'),
        GodanRow('ぬ', 'な', 'に', 'ね', 'の'),
        GodanRow('ぶ', 'ば', 'び', 'べ', 'ぼ'),
        GodanRow('む', 'ま', 'み', 'め', 'も'),
        GodanRow('る', 'ら', 'り', 'れ', 'ろ'),
    )

    val all: List<Rule> = buildList {
        // --- Stem (連用形) + auxiliary -------------------------------------------------------------
        fun stem(suffix: String, reason: String, condIn: Set<Cond>, v1: Boolean = true) {
            if (v1) add(Rule(suffix, "る", condIn, setOf(Cond.V1), reason))
            for (r in godanRows) add(Rule("${r.i}$suffix", "${r.u}", condIn, setOf(Cond.V5), reason))
            add(Rule("き$suffix", "くる", condIn, setOf(Cond.VK), reason))
            add(Rule("来$suffix", "来る", condIn, setOf(Cond.VK), reason))
            add(Rule("し$suffix", "する", condIn, setOf(Cond.VS), reason))
        }
        // Honorific godan (くださる, いらっしゃる…) use an い stem before ます.
        fun honorificStem(suffix: String, reason: String) {
            add(Rule("さい$suffix", "さる", PLAIN, setOf(Cond.V5), reason))
            add(Rule("しゃい$suffix", "しゃる", PLAIN, setOf(Cond.V5), reason))
            add(Rule("ざい$suffix", "ざる", PLAIN, setOf(Cond.V5), reason))
        }
        for ((suffix, reason) in listOf(
            "ます" to "polite",
            "ません" to "polite negative",
            "ました" to "polite past",
            "ませんでした" to "polite past negative",
            "ましょう" to "polite volitional",
            "まして" to "polite te",
            "ませんか" to "polite invitation",
        )) {
            stem(suffix, reason, PLAIN)
            honorificStem(suffix, reason)
        }
        stem("たい", "-tai", setOf(Cond.ADJ_I))
        stem("ながら", "-nagara", PLAIN)
        stem("なさい", "-nasai", PLAIN)
        stem("そう", "-sou", setOf(Cond.ADJ_NA))
        stem("すぎる", "-sugiru", setOf(Cond.V1))
        stem("やすい", "-yasui", setOf(Cond.ADJ_I))
        stem("にくい", "-nikui", setOf(Cond.ADJ_I))

        // --- 未然形 + auxiliary (negatives) ---------------------------------------------------------
        fun negative(suffix: String, reason: String, condIn: Set<Cond>, suru: String = "し$suffix") {
            add(Rule(suffix, "る", condIn, setOf(Cond.V1), reason))
            for (r in godanRows) add(Rule("${r.a}$suffix", "${r.u}", condIn, setOf(Cond.V5), reason))
            add(Rule("こ$suffix", "くる", condIn, setOf(Cond.VK), reason))
            add(Rule("来$suffix", "来る", condIn, setOf(Cond.VK), reason))
            add(Rule(suru, "する", condIn, setOf(Cond.VS), reason))
        }
        negative("ない", "negative", setOf(Cond.ADJ_I))
        add(Rule("ない", "ある", setOf(Cond.ADJ_I), setOf(Cond.V5), "negative", exact = true))
        negative("ないで", "negative te", PLAIN)
        negative("なきゃ", "-nakya", PLAIN)
        negative("なくちゃ", "-nakucha", PLAIN)
        negative("ず", "-zu", PLAIN, suru = "せず")
        negative("ずに", "-zuni", PLAIN, suru = "せずに")
        negative("ぬ", "-nu", PLAIN, suru = "せぬ")

        // Passive: 書かれる, 食べられる, 来られる, される. Result inflects as ichidan.
        add(Rule("られる", "る", setOf(Cond.V1), setOf(Cond.V1), "passive"))
        for (r in godanRows) add(Rule("${r.a}れる", "${r.u}", setOf(Cond.V1), setOf(Cond.V5), "passive"))
        add(Rule("こられる", "くる", setOf(Cond.V1), setOf(Cond.VK), "passive"))
        add(Rule("来られる", "来る", setOf(Cond.V1), setOf(Cond.VK), "passive"))
        add(Rule("される", "する", setOf(Cond.V1), setOf(Cond.VS), "passive"))

        // Causative: 書かせる, 食べさせる, 来させる, させる; short godan causative 書かす.
        add(Rule("させる", "る", setOf(Cond.V1), setOf(Cond.V1), "causative"))
        for (r in godanRows) add(Rule("${r.a}せる", "${r.u}", setOf(Cond.V1), setOf(Cond.V5), "causative"))
        add(Rule("こさせる", "くる", setOf(Cond.V1), setOf(Cond.VK), "causative"))
        add(Rule("来させる", "来る", setOf(Cond.V1), setOf(Cond.VK), "causative"))
        add(Rule("させる", "する", setOf(Cond.V1), setOf(Cond.VS), "causative"))
        for (r in godanRows) {
            if (r.u != 'す') add(Rule("${r.a}す", "${r.u}", setOf(Cond.V5), setOf(Cond.V5), "short causative"))
        }
        add(Rule("さす", "る", setOf(Cond.V5), setOf(Cond.V1), "short causative"))

        // Potential: godan e-row + る; ichidan られる / ら抜き れる; 来られる / 来れる; できる.
        for (r in godanRows) add(Rule("${r.e}る", "${r.u}", setOf(Cond.V1), setOf(Cond.V5), "potential"))
        add(Rule("られる", "る", setOf(Cond.V1), setOf(Cond.V1), "potential"))
        add(Rule("れる", "る", setOf(Cond.V1), setOf(Cond.V1), "potential"))
        add(Rule("これる", "くる", setOf(Cond.V1), setOf(Cond.VK), "potential"))
        add(Rule("来れる", "来る", setOf(Cond.V1), setOf(Cond.VK), "potential"))
        add(Rule("できる", "する", setOf(Cond.V1), setOf(Cond.VS), "potential"))

        // Volitional.
        for (r in godanRows) add(Rule("${r.o}う", "${r.u}", PLAIN, setOf(Cond.V5), "volitional"))
        add(Rule("よう", "る", PLAIN, setOf(Cond.V1), "volitional"))
        add(Rule("こよう", "くる", PLAIN, setOf(Cond.VK), "volitional"))
        add(Rule("来よう", "来る", PLAIN, setOf(Cond.VK), "volitional"))
        add(Rule("しよう", "する", PLAIN, setOf(Cond.VS), "volitional"))

        // Imperative.
        for (r in godanRows) add(Rule("${r.e}", "${r.u}", PLAIN, setOf(Cond.V5), "imperative"))
        add(Rule("ろ", "る", PLAIN, setOf(Cond.V1), "imperative"))
        add(Rule("よ", "る", PLAIN, setOf(Cond.V1), "imperative"))
        add(Rule("くれ", "くれる", PLAIN, setOf(Cond.V1), "imperative"))
        add(Rule("こい", "くる", PLAIN, setOf(Cond.VK), "imperative"))
        add(Rule("来い", "来る", PLAIN, setOf(Cond.VK), "imperative"))
        add(Rule("しろ", "する", PLAIN, setOf(Cond.VS), "imperative"))
        add(Rule("せよ", "する", PLAIN, setOf(Cond.VS), "imperative"))
        add(Rule("さい", "さる", PLAIN, setOf(Cond.V5), "imperative"))
        add(Rule("ざい", "ざる", PLAIN, setOf(Cond.V5), "imperative"))
        add(Rule("しゃい", "しゃる", PLAIN, setOf(Cond.V5), "imperative"))
        add(Rule("な", "", PLAIN, V, "prohibitive"))

        // Conditional ば.
        for (r in godanRows) add(Rule("${r.e}ば", "${r.u}", PLAIN, setOf(Cond.V5), "-ba"))
        add(Rule("れば", "る", PLAIN, setOf(Cond.V1), "-ba"))
        add(Rule("くれば", "くる", PLAIN, setOf(Cond.VK), "-ba"))
        add(Rule("来れば", "来る", PLAIN, setOf(Cond.VK), "-ba"))
        add(Rule("すれば", "する", PLAIN, setOf(Cond.VS), "-ba"))
        add(Rule("ければ", "い", PLAIN, setOf(Cond.ADJ_I), "-ba"))

        // --- て/た family with euphonic changes ------------------------------------------------------
        fun teFamily(t: String, reason: String, condIn: Set<Cond>) {
            val voiced = when (t[0]) {
                'て' -> "で" + t.drop(1)
                'た' -> "だ" + t.drop(1)
                else -> error("bad te family $t")
            }
            add(Rule(t, "る", condIn, setOf(Cond.V1), reason))
            add(Rule("い$t", "く", condIn, setOf(Cond.V5), reason))
            add(Rule("い$voiced", "ぐ", condIn, setOf(Cond.V5), reason))
            add(Rule("し$t", "す", condIn, setOf(Cond.V5), reason))
            for (u in listOf("う", "つ", "る")) add(Rule("っ$t", u, condIn, setOf(Cond.V5), reason))
            for (u in listOf("む", "ぬ", "ぶ")) add(Rule("ん$voiced", u, condIn, setOf(Cond.V5), reason))
            add(Rule("う$t", "う", condIn, setOf(Cond.V5), reason)) // 問うて, 請うた
            add(Rule("行っ$t", "行く", condIn, setOf(Cond.V5), reason))
            add(Rule("いっ$t", "いく", condIn, setOf(Cond.V5), reason))
            add(Rule("き$t", "くる", condIn, setOf(Cond.VK), reason))
            add(Rule("来$t", "来る", condIn, setOf(Cond.VK), reason))
            add(Rule("し$t", "する", condIn, setOf(Cond.VS), reason))
        }
        teFamily("て", "te", setOf(Cond.TE))
        teFamily("た", "past", PLAIN)
        teFamily("たら", "-tara", PLAIN)
        teFamily("たり", "-tari", PLAIN)
        teFamily("ても", "-temo", PLAIN)

        // Auxiliaries attached to the て form. Each peels back to a TE state.
        fun teAux(tSuffix: String, dSuffix: String, reason: String, condIn: Set<Cond>) {
            add(Rule(tSuffix, "て", condIn, setOf(Cond.TE), reason))
            add(Rule(dSuffix, "で", condIn, setOf(Cond.TE), reason))
        }
        teAux("ている", "でいる", "progressive", setOf(Cond.V1))
        teAux("てる", "でる", "progressive", setOf(Cond.V1))
        teAux("ておく", "でおく", "-teoku", setOf(Cond.V5))
        teAux("とく", "どく", "-teoku", setOf(Cond.V5))
        teAux("てある", "である", "-tearu", setOf(Cond.V5))
        teAux("てしまう", "でしまう", "-teshimau", setOf(Cond.V5))
        teAux("ちゃう", "じゃう", "-chau", setOf(Cond.V5))
        teAux("てみる", "でみる", "-temiru", setOf(Cond.V1))
        teAux("てくれる", "でくれる", "-tekureru", setOf(Cond.V1))
        teAux("てあげる", "であげる", "-teageru", setOf(Cond.V1))
        teAux("てもらう", "でもらう", "-temorau", setOf(Cond.V5))
        teAux("ていく", "でいく", "-teiku", setOf(Cond.V5))
        teAux("てくる", "でくる", "-tekuru", setOf(Cond.VK))
        teAux("てください", "でください", "-tekudasai", PLAIN)

        // --- い-adjectives -------------------------------------------------------------------------
        val adj = setOf(Cond.ADJ_I)
        add(Rule("くない", "い", adj, adj, "negative"))
        add(Rule("かった", "い", PLAIN, adj, "past"))
        add(Rule("かったら", "い", PLAIN, adj, "-tara"))
        add(Rule("かったり", "い", PLAIN, adj, "-tari"))
        add(Rule("くて", "い", PLAIN, adj, "te"))
        add(Rule("くても", "い", PLAIN, adj, "-temo"))
        add(Rule("く", "い", PLAIN, adj, "adverbial"))
        add(Rule("さ", "い", PLAIN, adj, "-sa"))
        add(Rule("そう", "い", setOf(Cond.ADJ_NA), adj, "-sou"))
        add(Rule("さそう", "い", setOf(Cond.ADJ_NA), adj, "-sou")) // なさそう, よさそう
        add(Rule("すぎる", "い", setOf(Cond.V1), adj, "-sugiru"))
        add(Rule("いです", "い", PLAIN, adj, "polite"))
        add(Rule("くありません", "い", PLAIN, adj, "polite negative"))
        add(Rule("くありませんでした", "い", PLAIN, adj, "polite past negative"))

        // --- Copula after な-adjectives (and です after anything plain) ----------------------------------
        val na = setOf(Cond.ADJ_NA)
        add(Rule("です", "", PLAIN, setOf(Cond.ADJ_NA, Cond.ADJ_I, Cond.PLAIN), "polite"))
        for ((suffix, reason) in listOf(
            "だ" to "copula",
            "だった" to "past",
            "だったら" to "-tara",
            "でした" to "polite past",
            "じゃありません" to "polite negative",
            "ではありません" to "polite negative",
            "じゃありませんでした" to "polite past negative",
            "ではありませんでした" to "polite past negative",
            "で" to "te",
            "なら" to "conditional",
            "な" to "attributive",
            "に" to "adverbial",
        )) add(Rule(suffix, "", PLAIN, na, reason))
        add(Rule("じゃない", "", adj, na, "negative"))
        add(Rule("ではない", "", adj, na, "negative"))
    }

    /** Rules indexed by the last character of [Rule.from] for fast suffix matching. */
    val byLastChar: Map<Char, List<Rule>> = all.groupBy { it.from.last() }
}
