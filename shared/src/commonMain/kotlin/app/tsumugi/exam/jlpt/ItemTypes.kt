package app.tsumugi.exam.jlpt

/**
 * JLPT item templates (BRIEF §5.11). Every type in the blueprint maps to one of these; the item bank stores
 * [key] in `exam_item.type`. [choices] is the number of options the real test uses.
 */
enum class JlptItemType(val key: String, val title: String, val english: String, val listening: Boolean = false, val choices: Int = 4) {
    KANJI_READING("kanji_reading", "漢字読み", "Kanji reading"),
    ORTHOGRAPHY("orthography", "表記", "Orthography"),
    WORD_FORMATION("word_formation", "語形成", "Word formation"),
    CONTEXT("context", "文脈規定", "Contextually defined expressions"),
    PARAPHRASE("paraphrase", "言い換え類義", "Paraphrases"),
    USAGE("usage", "用法", "Usage"),
    GRAMMAR_FORM("grammar_form", "文法形式の判断", "Grammar form"),
    SENTENCE_ASSEMBLY("sentence_assembly", "文の組み立て", "Sentence assembly (★)"),
    TEXT_GRAMMAR("text_grammar", "文章の文法", "Text grammar"),
    COMPREHENSION_SHORT("comprehension_short", "内容理解（短文）", "Comprehension: short passages"),
    COMPREHENSION_MID("comprehension_mid", "内容理解（中文）", "Comprehension: mid-size passages"),
    COMPREHENSION_LONG("comprehension_long", "内容理解（長文）", "Comprehension: long passages"),
    INTEGRATED("integrated", "統合理解", "Integrated comprehension"),
    THEMATIC("thematic", "主張理解（長文）", "Thematic comprehension"),
    INFO_RETRIEVAL("info_retrieval", "情報検索", "Information retrieval"),
    TASK("task", "課題理解", "Task-based comprehension", listening = true),
    POINT("point", "ポイント理解", "Comprehension of key points", listening = true),
    SUMMARY("summary", "概要理解", "Comprehension of general outline", listening = true),
    UTTERANCE("utterance", "発話表現", "Verbal expressions", listening = true, choices = 3),
    QUICK_RESPONSE("quick_response", "即時応答", "Quick response", listening = true, choices = 3),
    INTEGRATED_LISTENING("integrated_listening", "統合理解", "Integrated listening", listening = true),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String): JlptItemType? = byKey[key]
    }
}
