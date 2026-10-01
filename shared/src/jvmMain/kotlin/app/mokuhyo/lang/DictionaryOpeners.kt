package app.mokuhyo.lang

import java.io.File

/** How the registry opens a dictionary pack file; replaced by the dictionary module's opener once it exists. */
object DictionaryOpeners {
    val default: (File, String) -> DictionaryPack? = { _, _ -> null }
}
