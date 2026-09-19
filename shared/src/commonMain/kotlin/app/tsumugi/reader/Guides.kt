package app.tsumugi.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One free explanation elsewhere on the web (BRIEF_V2 §6.4, Tofugu-inspired guides library). **Links only**: the app
 * never fetches, caches or shows the page's content; tapping opens the system browser. [grammar] lists grammar-pack
 * point ids the guide explains; [topics] are broader tags ("particles", "keigo", "counters", "kana", …).
 */
@Serializable
data class GuideLink(
    val id: String,
    val title: String,
    val site: String,
    val url: String,
    val topics: List<String> = emptyList(),
    val grammar: List<String> = emptyList(),
)

/**
 * The curated guides library (DECISIONS D-166). The list is written by us for Tsumugi: titles are our own
 * descriptions and the URLs are stable pages of well-known free resources (Tae Kim's Guide, Tofugu, Imabi, Wasabi,
 * Sakubi, Kanshudo, JLPT Sensei, Nihongo no Mori, NHK). Every Tae Kim and Tofugu URL was checked to answer
 * HTTP 200 on 2026-09-18. Nothing from those pages is copied into the app.
 */
object GuidesLibrary {
    val all: List<GuideLink> by lazy { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(GuideLink.serializer()), JSON) }

    /** Guides that explain grammar point [pointId] (direct links first), then guides for its [topics]. */
    fun forGrammarPoint(pointId: String, topics: List<String> = emptyList()): List<GuideLink> {
        val direct = all.filter { pointId in it.grammar }
        val byTopic = all.filter { g -> g !in direct && g.topics.any { it in topics } }
        return direct + byTopic
    }

    fun forTopic(topic: String): List<GuideLink> = all.filter { topic in it.topics }

    /** Every topic tag, sorted. */
    val topics: List<String> get() = all.flatMap { it.topics }.distinct().sorted()

    /** The curated list. Topic tags are lowercase; grammar ids are grammar-pack point ids. */
    internal const val JSON: String = """
[
 {"id":"taekim-grammar","title":"Tae Kim's Guide to Learning Japanese: the complete grammar guide","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar","topics":["overview"]},
 {"id":"taekim-stateofbeing","title":"Expressing state-of-being with だ","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/stateofbeing","topics":["copula"],"grammar":["n5-da","n5-desu","n5-janai","n5-datta","n5-janakatta"]},
 {"id":"taekim-particles","title":"Introduction to particles: は, も and が","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/particlesintro","topics":["particles"],"grammar":["n5-wa-topic","n5-mo","n5-ga-subject"]},
 {"id":"taekim-adjectives","title":"Adjectives: な-adjectives and い-adjectives","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/adjectives","topics":["adjectives"],"grammar":["n5-i-adj","n5-na-adj","n5-i-adj-negative","n5-i-adj-past","n5-na-adj-negative"]},
 {"id":"taekim-verbs","title":"Verb basics: ru-verbs and u-verbs","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/verbs","topics":["verbs","conjugation"]},
 {"id":"taekim-negative","title":"Negative verbs","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/negativeverbs","topics":["verbs","conjugation"]},
 {"id":"taekim-past","title":"Past tense","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/past_tense","topics":["verbs","conjugation"],"grammar":["n5-deshita","n5-datta"]},
 {"id":"taekim-verbparticles","title":"Particles used with verbs: を, に, へ, で","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/verbparticles","topics":["particles"],"grammar":["n5-wo-object","n5-ni-time","n5-ni-destination","n5-he","n5-de-location","n5-de-means"]},
 {"id":"taekim-nounparticles","title":"Noun-related particles: と, や, とか, の","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/nounparticles","topics":["particles"],"grammar":["n5-to-and","n5-ya","n5-no-possessive"]},
 {"id":"taekim-clause","title":"Relative clauses and sentence order","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/clause","topics":["clauses"]},
 {"id":"taekim-polite","title":"Polite form and verb stems","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/polite","topics":["politeness"],"grammar":["n5-desu","n5-dewa-arimasen"]},
 {"id":"taekim-question","title":"The question marker か","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/question","topics":["questions"],"grammar":["n5-ka-question"]},
 {"id":"taekim-compound","title":"Compound sentences: て-form, から, ので, のに, が, けど","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/compound","topics":["conjunctions","te-form"],"grammar":["n5-kara-reason","n5-ga-but","n5-kedo","n5-adj-te"]},
 {"id":"taekim-teform","title":"Other uses of the て-form: ている, てある, ておく, ていく/てくる","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/teform","topics":["te-form"],"grammar":["n4-teiru-state","n4-tearu","n4-teoku","n4-teiku-tekuru"]},
 {"id":"taekim-potential","title":"Potential form","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/potential","topics":["verbs","conjugation"],"grammar":["n4-potential","n4-mieru-kikoeru"]},
 {"id":"taekim-surunaru","title":"Using する and なる with the に particle","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/surunaru","topics":["verbs"],"grammar":["n5-adj-ku-naru","n4-youninaru","n4-younisuru"]},
 {"id":"taekim-conditionals","title":"Conditionals: と, なら, ば, たら","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/conditionals","topics":["conditionals"],"grammar":["n4-ba","n4-tara","n4-nara","n4-to-conditional"]},
 {"id":"taekim-must","title":"Expressing \"must\" or \"have to\"","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/must","topics":["obligation"],"grammar":["n4-nakerebanaranai","n4-tewaikenai","n4-nakutemoii","n4-temoii"]},
 {"id":"taekim-desire","title":"Desire and suggestions: たい, ほしい, volitional, ばいい","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/desire","topics":["desire"],"grammar":["n4-tehoshii","n4-baii","n4-tarandou","n4-mashou"]},
 {"id":"taekim-actionclause","title":"Acting on relative clauses: quotation with と, って, という","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/actionclause","topics":["quotation"]},
 {"id":"taekim-define","title":"Defining and describing: という and its uses","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/define","topics":["quotation"]},
 {"id":"taekim-try","title":"Trying something out or attempting to do something","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/try","topics":["verbs"],"grammar":["n4-temiru","n4-tosuru"]},
 {"id":"taekim-volitional2","title":"Using the volitional form to express an intention","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/volitional2","topics":["intention"],"grammar":["n4-volitional","n4-tootteiru"]},
 {"id":"taekim-favors","title":"Giving and receiving: あげる, くれる, もらう","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/favors","topics":["giving-receiving"],"grammar":["n4-ageru","n4-kureru","n4-morau","n4-teageru","n4-tekureru","n4-temorau"]},
 {"id":"taekim-requests","title":"Making requests: ください, なさい, the command form","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/requests","topics":["requests"],"grammar":["n4-imperative","n4-na-prohibitive","n4-nasai","n4-naide-kudasai"]},
 {"id":"taekim-numbers","title":"Numbers and counting","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/numbers","topics":["counters","numbers"],"grammar":["n5-counters","n5-nanji","n5-ikutsu"]},
 {"id":"taekim-sentenceending","title":"Casual sentence-ending particles: ね, よ, よね","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/sentence_ending","topics":["particles"],"grammar":["n5-yo","n5-ne","n5-yone"]},
 {"id":"taekim-causative","title":"Causative and passive verbs","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/causative","topics":["verbs","conjugation"],"grammar":["n4-passive","n4-suffering-passive","n4-causative","n4-sasete-kudasai","n4-causative-passive"]},
 {"id":"taekim-honorific","title":"Honorific and humble forms (keigo)","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/honorific","topics":["keigo","politeness"]},
 {"id":"taekim-unintended","title":"Things that happen unintentionally: てしまう","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/unintended","topics":["te-form"],"grammar":["n4-teshimau"]},
 {"id":"taekim-genericnouns","title":"Special expressions with generic nouns: こと, ところ, もの","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/genericnouns","topics":["nominalization"],"grammar":["n4-kotogadekiru"]},
 {"id":"taekim-certainty","title":"Expressing various levels of certainty: かもしれない, でしょう, はず","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/certainty","topics":["certainty"],"grammar":["n4-kamoshirenai","n4-darou","n4-hazu"]},
 {"id":"taekim-amount","title":"Expressing amounts: だけ, のみ, しか, ばかり, すぎる","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/amount","topics":["amounts"]},
 {"id":"taekim-similarity","title":"Similarity and hearsay: よう, みたい, そう, らしい","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/similarity","topics":["hearsay","appearance"],"grammar":["n4-you-da","n4-mitai","n4-sou-appearance","n4-sou-hearsay","n4-rashii"]},
 {"id":"taekim-comparison","title":"Using 方 and よる for comparisons","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/comparison","topics":["comparison"],"grammar":["n5-no-hou-ga","n5-yori","n4-hougaii","n4-ba-hodo"]},
 {"id":"taekim-easyhard","title":"Easy and hard to do: やすい, にくい","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/easyhard","topics":["verbs"]},
 {"id":"taekim-timeactions","title":"Time-specific actions: ばかり, とたん, ながら, まくる","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/timeactions","topics":["time"]},
 {"id":"taekim-reasoning","title":"Reasoning and conclusions: わけ, ～とする","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/reasoning","topics":["reasoning"]},
 {"id":"taekim-should","title":"Things that should be a certain way: はず, べき, べく, べからず","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/should","topics":["obligation"],"grammar":["n4-hazu"]},
 {"id":"taekim-even","title":"Expressing the minimum expectation: でさえ, (で)すら, おろか","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/even","topics":["emphasis"]},
 {"id":"taekim-formal","title":"Formal expressions: である, ざる, ず","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/formal","topics":["written-style"],"grammar":["n4-zuni"]},
 {"id":"taekim-signs","title":"Showing signs of something: がる, ばかり, めく","site":"Tae Kim","url":"https://guidetojapanese.org/learn/grammar/signs","topics":["appearance"],"grammar":["n4-garu","n4-tagaru"]},
 {"id":"tofugu-grammar","title":"Tofugu's Japanese grammar guides (index)","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/","topics":["overview"]},
 {"id":"tofugu-wa","title":"The particle は","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-wa/","topics":["particles"],"grammar":["n5-wa-topic"]},
 {"id":"tofugu-ga","title":"The particle が","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-ga/","topics":["particles"],"grammar":["n5-ga-subject","n5-ga-but"]},
 {"id":"tofugu-wo","title":"The particle を","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-wo/","topics":["particles"],"grammar":["n5-wo-object"]},
 {"id":"tofugu-ni","title":"The particle に","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-ni/","topics":["particles"],"grammar":["n5-ni-time","n5-ni-destination","n5-ni-location"]},
 {"id":"tofugu-de","title":"The particle で","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-de/","topics":["particles"],"grammar":["n5-de-location","n5-de-means"]},
 {"id":"tofugu-he","title":"The particle へ","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-he/","topics":["particles"],"grammar":["n5-he"]},
 {"id":"tofugu-mo","title":"The particle も","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-mo/","topics":["particles"],"grammar":["n5-mo"]},
 {"id":"tofugu-to","title":"The particle と","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-to/","topics":["particles"],"grammar":["n5-to-and"]},
 {"id":"tofugu-ka","title":"The particle か","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-ka/","topics":["particles","questions"],"grammar":["n5-ka-question"]},
 {"id":"tofugu-yo","title":"The particle よ","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-yo/","topics":["particles"],"grammar":["n5-yo"]},
 {"id":"tofugu-ne","title":"The particle ね","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-ne/","topics":["particles"],"grammar":["n5-ne"]},
 {"id":"tofugu-kara","title":"The particle から","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-kara/","topics":["particles"],"grammar":["n5-kara-from","n5-kara-reason"]},
 {"id":"tofugu-made","title":"The particle まで","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-made/","topics":["particles"],"grammar":["n5-made"]},
 {"id":"tofugu-ya","title":"The particle や","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/particle-ya/","topics":["particles"],"grammar":["n5-ya","n5-nado"]},
 {"id":"tofugu-desu","title":"です","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/desu/","topics":["copula","politeness"],"grammar":["n5-desu"]},
 {"id":"tofugu-da","title":"だ","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/da/","topics":["copula"],"grammar":["n5-da"]},
 {"id":"tofugu-teform","title":"The て-form","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/te-form/","topics":["te-form","conjugation"],"grammar":["n5-adj-te"]},
 {"id":"tofugu-potential","title":"Potential form (〜れる / 〜られる)","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/verb-potential-form-reru/","topics":["conjugation"],"grammar":["n4-potential"]},
 {"id":"tofugu-passive","title":"Passive form (〜れる / 〜られる)","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/verb-passive-form-rareru/","topics":["conjugation"],"grammar":["n4-passive","n4-suffering-passive"]},
 {"id":"tofugu-causative","title":"Causative form (〜せる / 〜させる)","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/verb-causative-form-saseru/","topics":["conjugation"],"grammar":["n4-causative","n4-sasete-kudasai"]},
 {"id":"tofugu-volitional","title":"Volitional form (〜よう / 〜おう)","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/verb-volitional-form-you/","topics":["conjugation","intention"],"grammar":["n4-volitional","n4-mashou"]},
 {"id":"tofugu-ba","title":"Conditional form 〜ば","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/verb-conditional-form-ba/","topics":["conditionals"],"grammar":["n4-ba","n4-baii"]},
 {"id":"tofugu-tara","title":"Conditional 〜たら","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/conditional-form-tara/","topics":["conditionals"],"grammar":["n4-tara","n4-tarandou"]},
 {"id":"tofugu-kamoshirenai","title":"かもしれない","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/kamoshirenai/","topics":["certainty"],"grammar":["n4-kamoshirenai"]},
 {"id":"tofugu-temiru","title":"〜てみる","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/temiru/","topics":["te-form"],"grammar":["n4-temiru"]},
 {"id":"tofugu-teoku","title":"〜ておく","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/teoku/","topics":["te-form"],"grammar":["n4-teoku"]},
 {"id":"tofugu-tearu","title":"〜てある","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/tearu/","topics":["te-form"],"grammar":["n4-tearu"]},
 {"id":"tofugu-tsumori","title":"つもり","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/tsumori/","topics":["intention"],"grammar":["n4-tsumori"]},
 {"id":"tofugu-nagara","title":"〜ながら","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/nagara/","topics":["time"]},
 {"id":"tofugu-i-adjective","title":"い-adjectives","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/i-adjective/","topics":["adjectives"],"grammar":["n5-i-adj","n5-i-adj-negative","n5-i-adj-past"]},
 {"id":"tofugu-na-adjective","title":"な-adjectives","site":"Tofugu","url":"https://www.tofugu.com/japanese-grammar/na-adjective/","topics":["adjectives"],"grammar":["n5-na-adj","n5-na-adj-negative"]},
 {"id":"tofugu-counters","title":"A list of Japanese counters","site":"Tofugu","url":"https://www.tofugu.com/japanese/japanese-counters-list/","topics":["counters"],"grammar":["n5-counters"]},
 {"id":"tofugu-keigo","title":"An introduction to keigo","site":"Tofugu","url":"https://www.tofugu.com/japanese/keigo/","topics":["keigo","politeness"]},
 {"id":"tofugu-onomatopoeia","title":"Japanese onomatopoeia","site":"Tofugu","url":"https://www.tofugu.com/japanese/japanese-onomatopoeia/","topics":["onomatopoeia"]},
 {"id":"tofugu-rendaku","title":"Rendaku: why some sounds become voiced in compounds","site":"Tofugu","url":"https://www.tofugu.com/japanese/rendaku/","topics":["pronunciation"]},
 {"id":"tofugu-hiragana","title":"Learn hiragana","site":"Tofugu","url":"https://www.tofugu.com/japanese/learn-hiragana/","topics":["kana"]},
 {"id":"tofugu-katakana","title":"Learn katakana","site":"Tofugu","url":"https://www.tofugu.com/japanese/learn-katakana/","topics":["kana"]},
 {"id":"tofugu-typing","title":"How to type in Japanese","site":"Tofugu","url":"https://www.tofugu.com/japanese/how-to-type-in-japanese/","topics":["kana","tools"]},
 {"id":"imabi","title":"Imabi: an in-depth Japanese grammar course from beginner to classical Japanese","site":"Imabi","url":"https://imabi.org/","topics":["overview","classical"]},
 {"id":"wasabi","title":"Wasabi: Japanese grammar explanations and verb conjugation tables","site":"Wasabi","url":"https://www.wasabi-jpn.com/","topics":["overview","conjugation"]},
 {"id":"sakubi","title":"Sakubi: a fast, compact introduction to Japanese grammar","site":"Sakubi","url":"https://sakubi.neocities.org/","topics":["overview"]},
 {"id":"kanshudo-grammar","title":"Kanshudo grammar overview","site":"Kanshudo","url":"https://www.kanshudo.com/grammar/overview","topics":["overview"]},
 {"id":"jlptsensei-n5","title":"JLPT Sensei: N5 grammar list","site":"JLPT Sensei","url":"https://jlptsensei.com/jlpt-n5-grammar-list/","topics":["jlpt"]},
 {"id":"nihongonomori","title":"Nihongo no Mori: free video lessons for JLPT grammar (YouTube)","site":"日本語の森","url":"https://www.youtube.com/@nihongonomori2013","topics":["jlpt","video"]},
 {"id":"japanesewithanime","title":"Japanese with Anime: grammar and slang explained with anime examples","site":"Japanese with Anime","url":"https://www.japanesewithanime.com/","topics":["casual-speech"]},
 {"id":"nhk-easy","title":"NHK News Web Easy: news in simple Japanese with furigana","site":"NHK","url":"https://www3.nhk.or.jp/news/easy/","topics":["reading"]},
 {"id":"nhk-lessons","title":"NHK World: Easy Japanese lessons","site":"NHK","url":"https://www.nhk.or.jp/lesson/en/","topics":["overview","listening"]}
]
"""
}
