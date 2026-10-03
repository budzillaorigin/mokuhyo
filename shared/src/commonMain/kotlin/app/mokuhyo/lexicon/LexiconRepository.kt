package app.mokuhyo.lexicon

import app.mokuhyo.db.Lexicon_package
import app.mokuhyo.db.Lexicon_term
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.srs.ReviewService
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Clock

/**
 * Imported lexicon update packages (BRIEF_PHASE8 C-04). Terms are merged by (lang, domain, termId): the newest
 * package version for a track is current, unless the app's own shipped track is newer. Importing computes the delta
 * against what was current (the previous package, else the shipped track), stores the package append-only, and
 * queues the newly added terms into Review as new FSRS cards tagged "lexicon update".
 */
class LexiconRepository(private val db: MokuhyoDatabase, private val clock: Clock = Clock.System) {
    @Serializable
    data class DeltaIds(val added: List<String> = emptyList(), val changed: List<String> = emptyList(), val removed: List<String> = emptyList())

    data class ImportResult(val rowId: String, val delta: LexiconDelta, val alreadyImported: Boolean, val queued: Int)

    private val json get() = LexiconPackage.json

    /**
     * Stores [parsed] (refused when its terms don't match the manifest hash). [shipped] is the app's own track for the
     * package's language and domain (the baseline when nothing was imported yet). New terms go to [reviews].
     */
    fun import(
        parsed: LexiconPackage.Parsed, publisher: Publisher, origin: String, shipped: Track?, reviews: ReviewService?, learnerId: String,
    ): ImportResult {
        require(parsed.termsIntact) { "the package's terms don't match its manifest (damaged or altered)" }
        require(publisher !is Publisher.Invalid) { "the package's signature is invalid: ${(publisher as Publisher.Invalid).reason}" }
        val m = parsed.pkg.manifest
        val rowId = "${m.id}@${m.version}"
        db.lexiconQueries.packageById(rowId).executeAsOneOrNull()?.let { existing ->
            val ids = json.decodeFromString(DeltaIds.serializer(), existing.deltaJson)
            val terms = terms(rowId).associateBy { it.id }
            return ImportResult(rowId, LexiconDelta(ids.added.mapNotNull(terms::get), ids.changed.mapNotNull(terms::get), emptyList()), true, 0)
        }
        val before = current(m.lang, m.domain, shipped)?.second ?: shipped?.terms.orEmpty()
        val delta = LexiconDelta.between(before, parsed.pkg.terms)
        val now = clock.now().toEpochMilliseconds()
        db.transaction {
            db.lexiconQueries.insertPackage(
                Lexicon_package(
                    id = rowId, packageId = m.id, lang = m.lang, domain = m.domain, version = m.version, previousVersion = m.previousVersion,
                    publisher = m.publisher, created = m.created, sha256 = m.sha256, verified = if (publisher is Publisher.Verified) 1 else 0,
                    keyId = (publisher as? Publisher.Verified)?.keyId ?: parsed.pkg.signature?.keyId, origin = origin,
                    attribution = parsed.pkg.attribution, importedAt = now,
                    deltaJson = json.encodeToString(DeltaIds.serializer(), DeltaIds(delta.added.map { it.id }, delta.changed.map { it.id }, delta.removed.map { it.id })),
                    deleted = null,
                ),
            )
            parsed.pkg.terms.forEach { t ->
                db.lexiconQueries.insertTerm(Lexicon_term(rowId, m.lang, m.domain, t.id, json.encodeToString(TrackTerm.serializer(), t)))
            }
        }
        var queued = 0
        if (reviews != null) {
            delta.added.forEach { t ->
                val before = reviews.total(learnerId, m.lang)
                reviews.add(learnerId, m.lang, ReviewService.Kind.TERM, "term:${t.id}", t.term, "${t.termEn}\n${t.definition}", "lexicon update ${m.version}")
                if (reviews.total(learnerId, m.lang) > before) queued++
            }
        }
        return ImportResult(rowId, delta, false, queued)
    }

    /** Every imported package for [lang], newest import first. */
    fun packages(lang: String): List<Lexicon_package> = db.lexiconQueries.packages(lang).executeAsList()

    fun terms(rowId: String): List<TrackTerm> =
        db.lexiconQueries.termsOf(rowId).executeAsList().map { json.decodeFromString(TrackTerm.serializer(), it.json) }

    fun delta(p: Lexicon_package): DeltaIds = json.decodeFromString(DeltaIds.serializer(), p.deltaJson)

    /** The newest imported package for a track and its terms, when it is newer than the shipped track. */
    fun current(lang: String, domain: String, shipped: Track?): Pair<Lexicon_package, List<TrackTerm>>? {
        val best = db.lexiconQueries.packagesFor(lang, domain).executeAsList()
            .maxWithOrNull(compareBy<Lexicon_package, String>(SemVer::compare) { it.version }.thenBy { it.importedAt }) ?: return null
        if (shipped != null && SemVer.compare(best.version, shipped.version) <= 0) return null
        return best to terms(best.id)
    }

    /** [shipped] with the current imported terms overlaid by id (new terms appended), or [shipped] unchanged. */
    fun overlay(shipped: Track): Track {
        val (pkg, terms) = current(shipped.lang, shipped.id, shipped) ?: return shipped
        val byId = terms.associateBy { it.id }
        val merged = shipped.terms.map { byId[it.id] ?: it } + terms.filter { t -> shipped.terms.none { it.id == t.id } }
        return shipped.copy(terms = merged, version = pkg.version)
    }

    fun encodeTerms(terms: List<TrackTerm>): String = json.encodeToString(ListSerializer(TrackTerm.serializer()), terms)
}
