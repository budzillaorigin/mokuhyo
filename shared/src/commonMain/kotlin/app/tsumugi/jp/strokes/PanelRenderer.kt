package app.tsumugi.jp.strokes

/**
 * NihongoShark-style stroke-order panels (BRIEF §5.7): one 109×109 panel per stroke, with completed strokes in
 * gray, the current stroke in black and a red dot where it starts. Output is plain SVG text; the apps rasterize
 * it (e.g. to 109×109 PNGs for an Anki export). [paths] are KanjiVG SVG path data in stroke order.
 */
object PanelRenderer {
    private const val SIZE = 109
    private const val DONE = "#b0b0b0"
    private const val CURRENT = "#000000"
    private const val START = "#e0302a"

    /** Panel for stroke [index] (0-based): strokes before it gray, it black, its start point red. */
    fun panel(paths: List<String>, index: Int): String {
        require(index in paths.indices) { "stroke $index of ${paths.size}" }
        return svg(SIZE, SIZE, panelBody(paths, index, 0))
    }

    /** Every panel in one horizontal strip ([paths].size × 109 wide). */
    fun strip(paths: List<String>): String =
        svg(SIZE * paths.size, SIZE, paths.indices.joinToString("") { panelBody(paths, it, it * SIZE) })

    private fun panelBody(paths: List<String>, index: Int, offsetX: Int): String = buildString {
        append("<g transform=\"translate($offsetX,0)\">")
        append("<rect x=\"0.5\" y=\"0.5\" width=\"108\" height=\"108\" fill=\"#ffffff\" stroke=\"#dddddd\"/>")
        append("<path d=\"M54.5,0V109M0,54.5H109\" stroke=\"#eeeeee\" stroke-dasharray=\"3,3\" fill=\"none\"/>")
        append("<g fill=\"none\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\">")
        for (i in 0 until index) append("<path d=\"${escape(paths[i])}\" stroke=\"$DONE\"/>")
        append("<path d=\"${escape(paths[index])}\" stroke=\"$CURRENT\"/>")
        append("</g>")
        SvgPath.flatten(paths[index]).firstOrNull()?.let { p ->
            append("<circle cx=\"${fmt(p.x)}\" cy=\"${fmt(p.y)}\" r=\"3.5\" fill=\"$START\"/>")
        }
        append("</g>")
    }

    private fun svg(width: Int, height: Int, body: String) =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">$body</svg>"

    private fun escape(d: String) = d.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private fun fmt(v: Float): String {
        val r = kotlin.math.round(v * 100) / 100
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
}
