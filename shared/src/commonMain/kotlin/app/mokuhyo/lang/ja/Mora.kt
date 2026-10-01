package app.mokuhyo.lang.ja

/** Mora segmentation: 拗音 (きゃ, ふぁ, ヴァ …) is one mora; っ, ん and ー each count as one. */
object Mora {
    private const val SMALL = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"

    fun split(kana: String): List<String> {
        val out = ArrayList<String>(kana.length)
        for (c in kana) {
            val attach = c in SMALL && out.isNotEmpty() && out.last().let { prev ->
                prev.length == 1 && prev[0] !in "っッーんン" && prev[0] !in SMALL
            }
            if (attach) out[out.lastIndex] = out.last() + c else out += c.toString()
        }
        return out
    }

    fun count(kana: String): Int = split(kana).size
}
