package app.tsumugi.thesaurus

import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.dictionary.db.Expression_cluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** emotion (怒り, 安堵 …) or scene (雨, 夜, 表情 …). */
enum class ClusterKind { EMOTION, SCENE }

data class ExpressionCluster(
    val id: String,
    val kind: ClusterKind,
    val ja: String,
    val reading: String,
    val en: String,
    val description: String,
    /** llm until reviewed (rule 10: the UI shows the AI badge on the cluster and its drafted examples). */
    val source: String,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

/** A Tatoeba sentence (human translation, no badge). */
data class ThesaurusExample(val sentenceId: Long, val ja: String, val en: String)

data class ThesaurusExpression(
    val text: String,
    val reading: String,
    /** The JMdict entry (tap through to the dictionary), null for set phrases JMdict lacks. */
    val entryId: Long?,
    /** JMdict's first-sense glosses (CC BY-SA, EDRDG). */
    val gloss: String,
    val nuance: String,
    /** casual | neutral | formal | literary. */
    val register: String,
    /** 1 (mild) … 3 (strong). */
    val intensity: Int,
    /** The drafted example (labeled with the cluster's source). */
    val exampleJa: String,
    val exampleEn: String,
    val tatoeba: List<ThesaurusExample>,
)

data class ClusterDetail(val cluster: ExpressionCluster, val expressions: List<ThesaurusExpression>)

/** NV = noun + particle + verb, AN = adjective + noun, AV = adverb + verb (D-273). */
enum class CollocationPattern { NV, AN, AV }

data class CollocationPair(
    val pattern: CollocationPattern,
    /** The first word (noun for NV, adjective for AN, adverb for AV) and the second (verb or noun). */
    val firstId: Long,
    val firstText: String,
    val particle: String,
    val secondId: Long,
    val secondText: String,
    val count: Int,
    val pmi: Double,
    val example: ThesaurusExample?,
) {
    /** "雨が降る", "強い雨", "静かな夜", "ゆっくり歩く" (particle: the case particle, な for な-adjectives, else ""). */
    val phrase: String get() = "$firstText$particle$secondText"
}

/**
 * The expression thesaurus and collocations (BRIEF_V2 §6.13; D-272, D-273), tables in the dictionary pack. A pack
 * built before Phase 13 lacks them: every call returns empty and [available] is false (honest empty state).
 */
class ThesaurusRepository(private val pack: DictionaryDatabase) {
    private val q get() = pack.thesaurusQueries

    @Throws(Exception::class)
    suspend fun available(): Boolean = clusters().isNotEmpty()

    @Throws(Exception::class)
    suspend fun clusters(kind: ClusterKind? = null): List<ExpressionCluster> = io {
        runCatching { q.clusters().executeAsList() }.getOrDefault(emptyList()).map { it.toCluster() }.filter { kind == null || it.kind == kind }
    }

    @Throws(Exception::class)
    suspend fun search(query: String): List<ExpressionCluster> = io {
        val text = query.trim()
        if (text.isEmpty()) return@io emptyList()
        runCatching { q.searchClusters(text).executeAsList() }.getOrDefault(emptyList()).map { it.toCluster() }
    }

    @Throws(Exception::class)
    suspend fun cluster(id: String): ClusterDetail? = io {
        val c = runCatching { q.clusterById(id).executeAsOneOrNull() }.getOrNull() ?: return@io null
        val examples = q.examplesOf(id).executeAsList().groupBy({ it.ord }, { ThesaurusExample(it.id, it.ja, it.en) })
        val expressions = q.expressionsOf(id).executeAsList().map {
            ThesaurusExpression(
                it.text, it.reading, it.entry_id, it.gloss, it.nuance, it.register, it.intensity.toInt(), it.example_ja, it.example_en,
                examples[it.ord].orEmpty(),
            )
        }
        ClusterDetail(c.toCluster(), expressions)
    }

    /** Clusters that improve on each of [lemmas] (analyzer dictionary forms), for the writing studio's flags. */
    @Throws(Exception::class)
    suspend fun clustersForLemmas(lemmas: Collection<String>): Map<String, List<String>> = io {
        if (lemmas.isEmpty()) return@io emptyMap()
        runCatching { lemmas.distinct().chunked(500).flatMap { q.clustersForLemmas(it).executeAsList() } }.getOrDefault(emptyList())
            .groupBy({ it.lemma }, { it.cluster_id })
    }

    /** Clusters listing the JMdict entry [entryId] (the dictionary's "see also: expressions" link). */
    @Throws(Exception::class)
    suspend fun clustersForEntry(entryId: Long): List<String> = io {
        runCatching { q.clustersForEntry(entryId).executeAsList() }.getOrDefault(emptyList())
    }

    /** Strongest collocations of [entryId] in either position, best first (PMI weighted by frequency). */
    @Throws(Exception::class)
    suspend fun collocations(entryId: Long, limit: Int = 30): List<CollocationPair> = io {
        runCatching { q.collocationsOf(entryId, limit.toLong()).executeAsList() }.getOrDefault(emptyList()).map { r ->
            val example = r.example_id?.let { id -> q.collocationSentence(id).executeAsOneOrNull()?.let { ThesaurusExample(it.id, it.ja, it.en) } }
            CollocationPair(
                CollocationPattern.valueOf(r.pattern), r.a_id, r.a_text, r.particle, r.b_id, r.b_text, r.count.toInt(), r.pmi, example,
            )
        }
    }

    private fun Expression_cluster.toCluster() = ExpressionCluster(
        id, if (kind == "emotion") ClusterKind.EMOTION else ClusterKind.SCENE, ja, reading, en, description,
        if (verified != 0L) "verified" else source,
    )

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
