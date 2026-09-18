package app.tsumugi.integrations.anki

/** Anki's note field separator (ASCII unit separator). */
internal val FIELD_SEP: Char = 31.toChar()

/** HTML/field helpers matching what Anki itself does for sort fields and checksums. */
internal object AnkiText {
    private val breaks = Regex("(?i)<br\\s*/?>|</div>|</p>|</li>")
    private val tags = Regex("<[^>]*>")
    private val sound = Regex("\\[sound:[^\\]]*]")
    private val entities = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'")
    private val numeric = Regex("&#(x?[0-9a-fA-F]+);")

    /** Field → plain text, keeping line breaks (for stories and meaning lists). */
    fun plain(html: String): String {
        var s = html.replace(breaks, "\n").replace(sound, "").replace(tags, "")
        for ((k, v) in entities) s = s.replace(k, v)
        s = s.replace(numeric) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x") || v.startsWith("X")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            code?.let { c -> if (c <= 0xFFFF) c.toChar().toString() else m.value } ?: m.value
        }
        return s.lines().joinToString("\n") { it.trim() }.trim()
    }

    /** One-line plain text (Anki's `sfld` and checksum input). */
    fun oneLine(html: String): String = plain(html).replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    /** Anki's `csum`: first 8 hex digits of SHA-1 of the stripped first field, as an integer. */
    fun checksum(firstField: String): Long = Sha1.hex(oneLine(firstField).encodeToByteArray()).take(8).toLong(16)

    fun splitList(text: String): List<String> =
        plain(text).split(Regex("[,;、，；/\n]")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun htmlEscape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    private val mediaRefs = Regex("(?i)src=[\"']([^\"']+)[\"']|\\[sound:([^\\]]+)]")

    fun mediaReferences(field: String): List<String> =
        mediaRefs.findAll(field).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    /** Field names compared loosely: "My Story", "my_story" and "myStory" all become "mystory". */
    fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
}
