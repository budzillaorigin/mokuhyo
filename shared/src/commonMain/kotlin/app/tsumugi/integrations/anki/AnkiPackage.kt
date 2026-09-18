package app.tsumugi.integrations.anki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Which collection file an .apkg carried. */
enum class AnkiPackageFormat {
    /** `collection.anki2` only (Anki ≤ 2.1.x, "support older versions"). */
    LEGACY_ANKI2,
    /** `collection.anki21` (Anki 2.1.x). */
    LEGACY_ANKI21,
    /** `collection.anki21b`, zstd-compressed, with zstd media (Anki ≥ 2.1.50). */
    ZSTD_ANKI21B,
}

/** An unpacked .apkg: the SQLite collection bytes and media files by their real names. */
class AnkiPackage(val format: AnkiPackageFormat, val collection: ByteArray, val media: Map<String, ByteArray>) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun read(apkg: ByteArray): AnkiPackage {
            val entries = Zip.read(apkg)
            val (format, collection) = when {
                "collection.anki21b" in entries -> AnkiPackageFormat.ZSTD_ANKI21B to Zstd.decompress(entries.getValue("collection.anki21b"))
                "collection.anki21" in entries -> AnkiPackageFormat.LEGACY_ANKI21 to entries.getValue("collection.anki21")
                "collection.anki2" in entries -> AnkiPackageFormat.LEGACY_ANKI2 to entries.getValue("collection.anki2")
                else -> throw AnkiFormatException("no Anki collection in package")
            }
            val mediaMap: Map<String, String> = entries["media"]?.let { raw ->
                if (Zstd.isZstd(raw)) mediaEntries(Zstd.decompress(raw)) else legacyMediaMap(raw)
            }.orEmpty()
            val media = mediaMap.mapNotNull { (zipName, realName) ->
                entries[zipName]?.let { realName to if (Zstd.isZstd(it)) Zstd.decompress(it) else it }
            }.toMap()
            return AnkiPackage(format, collection, media)
        }

        /** Legacy media map: JSON {"0": "file.png", …}. */
        private fun legacyMediaMap(raw: ByteArray): Map<String, String> {
            val text = raw.decodeToString().trim()
            if (text.isEmpty()) return emptyMap()
            return (json.parseToJsonElement(text) as JsonObject).mapValues { it.value.jsonPrimitive.content }
        }

        /**
         * Modern media map: protobuf MediaEntries { repeated MediaEntry entries = 1; }
         * MediaEntry { string name = 1; uint32 size = 2; bytes sha1 = 3; optional uint32 legacy_zip_filename = 255; }
         * Zip member names are the entry index unless legacy_zip_filename is set.
         */
        internal fun mediaEntries(raw: ByteArray): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var index = 0
            ProtoReader(raw).forEachField { field, value ->
                if (field == 1 && value is ByteArray) {
                    var name = ""
                    var legacy: Long? = null
                    ProtoReader(value).forEachField { f, v ->
                        when {
                            f == 1 && v is ByteArray -> name = v.decodeToString()
                            f == 255 && v is Long -> legacy = v
                        }
                    }
                    out[(legacy ?: index.toLong()).toString()] = name
                    index++
                }
            }
            return out
        }
    }
}

/** Just enough protobuf wire-format parsing for Anki's media map and config blobs. */
internal class ProtoReader(private val data: ByteArray) {
    private var p = 0

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = data[p++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    /** Calls [block] with (field number, value): Long for varints/fixed, ByteArray for length-delimited. */
    fun forEachField(block: (Int, Any) -> Unit) {
        while (p < data.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> block(field, varint())
                1 -> { block(field, fixed(8)) }
                2 -> {
                    val len = varint().toInt()
                    block(field, data.copyOfRange(p, p + len))
                    p += len
                }
                5 -> block(field, fixed(4))
                else -> throw AnkiFormatException("unsupported protobuf wire type")
            }
        }
    }

    private fun fixed(n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((data[p + i].toLong() and 0xFF) shl (8 * i))
        p += n
        return v
    }
}
