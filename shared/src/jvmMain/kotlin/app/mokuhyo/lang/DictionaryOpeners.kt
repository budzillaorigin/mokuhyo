package app.mokuhyo.lang

import app.mokuhyo.dictionary.DictionaryPacks
import java.io.File

/** How the registry opens a dictionary pack file (read-only SQLite, language-neutral schema of BRIEF §5.2). */
object DictionaryOpeners {
    val default: (File, String) -> DictionaryPack? = { file, lang -> runCatching { DictionaryPacks.open(file, lang) }.getOrNull() }
}
