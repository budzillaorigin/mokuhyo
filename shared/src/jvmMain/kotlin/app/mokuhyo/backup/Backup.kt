package app.mokuhyo.backup

import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.srs.ReviewService
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Export and merge-import of `.mokuhyo` bundles (BRIEF §8.2). Blocking; call on Dispatchers.IO. [progress] gets
 * 0..1 and a step label; [cancelled] is polled between steps and chunks.
 */
class Backup(
    /** The learner database file (merges run on their own JDBC connection so ATTACH and the transaction share it). */
    private val dbFile: File,
    private val db: MokuhyoDatabase,
    private val dataDir: File,
    private val learnerId: String,
    private val appVersion: String,
) {
    class Cancelled : Exception("cancelled")

    data class ImportReport(
        val added: Map<String, Long>,
        val tombstones: Long,
        val recordingsCopied: Int,
        /** Learner settings that differ between this computer and the bundle: (key, here, bundle). Not applied. */
        val settingConflicts: List<Triple<String, String, String>>,
        val missingPacks: List<String>,
        val bundleCreated: String,
        val fromLearner: String,
    ) {
        val total: Long get() = added.values.sum()
    }

    private val tables = listOf("learner", "attempt", "conversation", "recording", "ilr_estimate", "review_item", "review", "generated_passage",
        "lexicon_package", "lexicon_term")

    fun export(
        out: File, languages: List<String>, packs: Bundle.PacksList, passphrase: String? = null,
        progress: (Double, String) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
    ): Bundle.Manifest {
        val work = File.createTempFile("mokuhyo-export", "").apply { delete(); mkdirs() }
        try {
            progress(0.02, "Copying the database")
            val dbCopy = File(work, "db.sqlite")
            sql { it.createStatement().use { st -> st.execute("VACUUM INTO '${dbCopy.absolutePath.replace("'", "''")}'") } }
            check(!cancelled()) { throw Cancelled() }
            val recordings = queryStrings("SELECT path FROM recording").map { File(dataDir, it) }.filter { it.isFile }.distinctBy { it.path }
            val settings = queryPairs("SELECT key, value FROM setting WHERE scope = 'learner'").toMap()
            val zip = File(work, "payload.zip")
            val checksums = LinkedHashMap<String, String>()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { z ->
                fun put(name: String, file: File) {
                    z.putNextEntry(ZipEntry(name))
                    val md = MessageDigest.getInstance("SHA-256")
                    FileInputStream(file).use { input -> copy(input) { buf, n -> z.write(buf, 0, n); md.update(buf, 0, n) } }
                    z.closeEntry()
                    checksums[name] = Bundle.hex(md.digest())
                }
                fun putBytes(name: String, bytes: ByteArray) {
                    z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
                    checksums[name] = sha(bytes)
                }
                put("db.sqlite", dbCopy)
                putBytes("settings.json", Bundle.json.encodeToString(MapSerializer(String.serializer(), String.serializer()), settings).encodeToByteArray())
                putBytes("packs.json", Bundle.json.encodeToString(Bundle.PacksList.serializer(), packs).encodeToByteArray())
                recordings.forEachIndexed { i, f ->
                    if (cancelled()) throw Cancelled()
                    progress(0.1 + 0.6 * i / recordings.size.coerceAtLeast(1), "Adding recordings")
                    put(f.relativeTo(dataDir).invariantSeparatorsPath, f)
                }
                val manifest = manifest(languages, checksums)
                putBytes("manifest.json", Bundle.json.encodeToString(Bundle.Manifest.serializer(), manifest).encodeToByteArray())
            }
            progress(0.75, if (passphrase != null) "Encrypting" else "Writing")
            writeContainer(zip, out, passphrase, cancelled) { p -> progress(0.75 + 0.25 * p, if (passphrase != null) "Encrypting" else "Writing") }
            progress(1.0, "Done")
            return ZipFile(zip).use { z -> Bundle.json.decodeFromString(Bundle.Manifest.serializer(), z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()) }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun manifest(languages: List<String>, checksums: Map<String, String>) = Bundle.Manifest(
        appVersion = appVersion, created = Instant.now().toString(), learnerId = learnerId,
        learnerName = queryStrings("SELECT displayName FROM learner WHERE id = '${learnerId.replace("'", "''")}'").firstOrNull() ?: "",
        languages = languages, schemaVersion = MokuhyoDatabase.Schema.version,
        counts = tables.associateWith { t -> queryLong("SELECT count(*) FROM $t") }, checksums = checksums,
    )

    private fun writeContainer(zip: File, out: File, passphrase: String?, cancelled: () -> Boolean, progress: (Double) -> Unit) {
        val tmp = File(out.parentFile ?: File("."), out.name + ".part")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
            if (passphrase == null) {
                o.write(Bundle.encodeHeader(Bundle.Header(created = Instant.now().toString(), appVersion = appVersion, encrypted = false)))
                FileInputStream(zip).use { copy(it) { buf, n -> o.write(buf, 0, n) } }
            } else {
                val kdf = Bundle.newKdf()
                val prefixHex = Bundle.newNoncePrefix()
                val headerBytes = Bundle.encodeHeader(Bundle.Header(created = Instant.now().toString(), appVersion = appVersion, encrypted = true, kdf = kdf, noncePrefix = prefixHex))
                o.write(headerBytes)
                val key = Bundle.deriveKey(passphrase, kdf)
                val prefix = Bundle.hex(prefixHex)
                val total = zip.length()
                val chunks = ((total + Bundle.CHUNK - 1) / Bundle.CHUNK).coerceAtLeast(1)
                DataInputStream(FileInputStream(zip)).use { input ->
                    for (i in 0 until chunks) {
                        if (cancelled()) throw Cancelled()
                        val size = minOf(Bundle.CHUNK.toLong(), total - i * Bundle.CHUNK).toInt().coerceAtLeast(0)
                        val plain = ByteArray(size).also { input.readFully(it) }
                        val sealed = Bundle.sealChunk(key, prefix, headerBytes, i, i == chunks - 1, plain)
                        o.writeInt(sealed.size)
                        o.write(sealed)
                        progress((i + 1).toDouble() / chunks)
                    }
                }
            }
        }
        if (!tmp.renameTo(out)) { out.delete(); check(tmp.renameTo(out)) { "couldn't write ${out.path}" } }
    }

    /** True when [file] is an encrypted bundle (the import screen asks for the passphrase first). */
    fun isEncrypted(file: File): Boolean = Bundle.decodeHeader(readHead(file)).first.encrypted

    fun importBundle(file: File, passphrase: String? = null, progress: (Double, String) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): ImportReport {
        val work = File.createTempFile("mokuhyo-import", "").apply { delete(); mkdirs() }
        try {
            progress(0.05, "Reading")
            val zip = File(work, "payload.zip")
            unwrap(file, zip, passphrase, cancelled)
            ZipFile(zip).use { z ->
                val manifest = Bundle.json.decodeFromString(Bundle.Manifest.serializer(), z.getInputStream(z.getEntry("manifest.json") ?: error("bundle has no manifest")).readBytes().decodeToString())
                require(manifest.format <= Bundle.FORMAT_VERSION) { "this bundle needs a newer Mokuhyo" }
                require(manifest.schemaVersion <= MokuhyoDatabase.Schema.version) { "this bundle was made by a newer Mokuhyo (database ${manifest.schemaVersion}); update the app" }
                progress(0.3, "Checking")
                manifest.checksums.forEach { (name, expected) ->
                    if (cancelled()) throw Cancelled()
                    val entry = z.getEntry(name) ?: error("bundle is missing $name")
                    val md = MessageDigest.getInstance("SHA-256")
                    z.getInputStream(entry).use { copy(it) { buf, n -> md.update(buf, 0, n) } }
                    require(Bundle.hex(md.digest()) == expected) { "$name is damaged (checksum mismatch)" }
                }
                val dbFile = File(work, "incoming.sqlite")
                z.getInputStream(z.getEntry("db.sqlite")).use { input -> FileOutputStream(dbFile).use { input.copyTo(it) } }
                // An older bundle's database is migrated to the current schema before merging.
                DatabaseFactory.open(dbFile).first.close()
                progress(0.5, "Merging")
                val (added, tombstones, items) = merge(dbFile, manifest.learnerId)
                progress(0.75, "Copying recordings")
                var copied = 0
                z.entries().asSequence().filter { it.name.startsWith("recordings/") && !it.isDirectory }.forEach { e ->
                    if (cancelled()) throw Cancelled()
                    val target = File(dataDir, e.name)
                    if (!target.isFile) {
                        target.parentFile.mkdirs()
                        z.getInputStream(e).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                        copied++
                    }
                }
                val reviews = ReviewService(db)
                items.forEach { reviews.recompute(it) }
                val incomingSettings = z.getEntry("settings.json")?.let { e ->
                    Bundle.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), z.getInputStream(e).readBytes().decodeToString())
                }.orEmpty()
                val local = queryPairs("SELECT key, value FROM setting WHERE scope = 'learner'").toMap()
                val conflicts = incomingSettings.filter { (k, v) -> k in local && local[k] != v && k != "learner.id" }.map { (k, v) -> Triple(k, local.getValue(k), v) }
                incomingSettings.filter { (k, _) -> k !in local && k != "learner.id" }.forEach { (k, v) -> db.settingsQueries.put(k, v, "learner") }
                val packs = z.getEntry("packs.json")?.let { Bundle.json.decodeFromString(Bundle.PacksList.serializer(), z.getInputStream(it).readBytes().decodeToString()) }
                progress(1.0, "Done")
                return ImportReport(added, tombstones, copied, conflicts, packs?.models.orEmpty(), manifest.created, manifest.learnerId)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readHead(file: File): ByteArray = FileInputStream(file).use { it.readNBytes(64 * 1024) }

    private fun unwrap(file: File, zip: File, passphrase: String?, cancelled: () -> Boolean) {
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            val fixed = ByteArray(12)
            try { input.readFully(fixed) } catch (e: java.io.EOFException) { throw IllegalArgumentException("not a .mokuhyo bundle") }
            val len = Bundle.getU32(fixed, 8)
            require(String(fixed, 0, 4, Charsets.US_ASCII) == Bundle.MAGIC) { "not a .mokuhyo bundle" }
            val headerBytes = fixed + ByteArray(len).also { input.readFully(it) }
            val (header, _) = Bundle.decodeHeader(headerBytes)
            FileOutputStream(zip).use { out ->
                if (!header.encrypted) {
                    input.copyTo(out)
                    return
                }
                val pass = passphrase ?: throw IllegalArgumentException("this bundle is encrypted; enter its passphrase")
                val key = Bundle.deriveKey(pass, header.kdf ?: error("encrypted bundle without KDF parameters"))
                val prefix = Bundle.hex(header.noncePrefix ?: error("encrypted bundle without nonce"))
                var index = 0L
                var pending: ByteArray? = null
                while (true) {
                    if (cancelled()) throw Cancelled()
                    val next = runCatching { ByteArray(input.readInt()).also { input.readFully(it) } }.getOrNull()
                    pending?.let { sealed ->
                        val plain = Bundle.openChunk(key, prefix, headerBytes, index, final = next == null, sealed)
                            ?: throw IllegalArgumentException("wrong passphrase, or the bundle is damaged")
                        out.write(plain)
                        index++
                    }
                    if (next == null) break
                    pending = next
                }
                require(index > 0) { "empty encrypted bundle" }
            }
        }
    }

    /**
     * Merges the incoming database by ids (never overwriting): every row of the bundle's learner is attributed to this
     * computer's learner; review items that already exist here under the same (language, kind, ref) keep the local id
     * and the incoming reviews are re-keyed to it; tombstones propagate. Returns rows added per table, tombstones
     * applied, and the review items to recompute.
     */
    private fun merge(incoming: File, fromLearner: String): Triple<Map<String, Long>, Long, Set<String>> = sql { c ->
        val me = learnerId.replace("'", "''")
        val them = fromLearner.replace("'", "''")
        val added = LinkedHashMap<String, Long>()
        var tombstones = 0L
        val affected = HashSet<String>()
        fun exec(q: String) = c.createStatement().use { it.execute(q) }
        fun long(q: String) = c.createStatement().use { st -> st.executeQuery(q).use { r -> if (r.next()) r.getLong(1) else 0L } }
        fun strings(q: String) = c.createStatement().use { st -> st.executeQuery(q).use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
        exec("ATTACH DATABASE '${incoming.absolutePath.replace("'", "''")}' AS incoming")
        try {
            c.autoCommit = false
            fun count(t: String) = long("SELECT count(*) FROM main.$t")
            fun insert(t: String, select: String) {
                val before = count(t)
                exec("INSERT OR IGNORE INTO main.$t $select")
                added[t] = count(t) - before
            }
            fun learner(col: String) = "CASE WHEN $col = '$them' THEN '$me' ELSE $col END"
            insert("attempt", "SELECT id, ${learner("learnerId")}, lang, modality, mode, bank, startedAt, submittedAt, formJson, answersJson, scoreJson, ilrEstimate, provisional, deleted FROM incoming.attempt")
            insert("conversation", "SELECT id, ${learner("learnerId")}, lang, kind, topic, startedAt, endedAt, turnsJson, ratingJson, rollingLevelJson, audioDir, deleted FROM incoming.conversation")
            insert("recording", "SELECT * FROM incoming.recording")
            insert("ilr_estimate", "SELECT id, ${learner("learnerId")}, lang, modality, at, value, sourceKind, sourceId, confidence, provisional, deleted FROM incoming.ilr_estimate")
            insert("generated_passage", "SELECT id, ${learner("learnerId")}, lang, skill, level, textType, title, body, scriptJson, itemsJson, audioPath, engine, createdAt, deleted FROM incoming.generated_passage")
            // Imported lexicon updates (BRIEF_PHASE8 C-04) travel with the learner: packages and their terms, by id.
            insert("lexicon_package", "SELECT * FROM incoming.lexicon_package")
            insert("lexicon_term", "SELECT * FROM incoming.lexicon_term")
            // Review items: map incoming ids onto existing (learner, lang, kind, ref) rows.
            exec("CREATE TEMP TABLE item_map AS SELECT i.id AS inId, COALESCE(m.id, i.id) AS outId FROM incoming.review_item i " +
                "LEFT JOIN main.review_item m ON m.learnerId = ${learner("i.learnerId")} AND m.lang = i.lang AND m.kind = i.kind AND m.ref = i.ref")
            insert("review_item", "SELECT i.id, ${learner("i.learnerId")}, i.lang, i.kind, i.ref, i.front, i.back, i.context, i.createdAt, i.deleted FROM incoming.review_item i " +
                "JOIN temp.item_map mp ON mp.inId = i.id WHERE mp.outId = i.id")
            insert("review", "SELECT r.id, mp.outId, r.at, r.rating, r.durationMs, r.deleted FROM incoming.review r JOIN temp.item_map mp ON mp.inId = r.itemId")
            affected += strings("SELECT DISTINCT mp.outId FROM incoming.review r JOIN temp.item_map mp ON mp.inId = r.itemId")
            // Tombstones are facts too: a deletion on either computer stays a deletion.
            for (t in listOf("attempt", "conversation", "recording", "ilr_estimate", "generated_passage", "review", "lexicon_package")) {
                val before = long("SELECT count(*) FROM main.$t WHERE deleted IS NOT NULL")
                exec("UPDATE main.$t SET deleted = (SELECT deleted FROM incoming.$t x WHERE x.id = main.$t.id) " +
                    "WHERE deleted IS NULL AND id IN (SELECT id FROM incoming.$t WHERE deleted IS NOT NULL)")
                tombstones += long("SELECT count(*) FROM main.$t WHERE deleted IS NOT NULL") - before
            }
            exec("DROP TABLE temp.item_map")
            c.commit()
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
            exec("DETACH DATABASE incoming")
        }
        Triple(added, tombstones, affected)
    }

    private fun <T> sql(block: (java.sql.Connection) -> T): T =
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout = 10000") }
            block(c)
        }

    private fun queryLong(q: String): Long = sql { c -> c.createStatement().use { st -> st.executeQuery(q).use { r -> if (r.next()) r.getLong(1) else 0L } } }

    private fun queryStrings(q: String): List<String> =
        sql { c -> c.createStatement().use { st -> st.executeQuery(q).use { r -> buildList { while (r.next()) r.getString(1)?.let { add(it) } } } } }

    private fun queryPairs(q: String): List<Pair<String, String>> =
        sql { c -> c.createStatement().use { st -> st.executeQuery(q).use { r -> buildList { while (r.next()) add((r.getString(1) ?: "") to (r.getString(2) ?: "")) } } } }

    private inline fun copy(input: InputStream, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            sink(buf, n)
        }
    }

    private fun sha(b: ByteArray) = Bundle.hex(MessageDigest.getInstance("SHA-256").digest(b))
}
