package app.tsumugi.integrations.wanikani

import app.tsumugi.platform.Secrets
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class FakeSecrets : Secrets {
    val values = HashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

/**
 * A scripted WaniKani API. [routes] maps "METHOD /path" (query ignored) to a handler; every request is
 * recorded in [requests]. Handlers see the full request (query parameters, headers).
 */
class FakeWaniKani {
    val requests = ArrayList<HttpRequestData>()
    val routes = HashMap<String, MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()

    val engine = MockEngine { request ->
        requests += request
        val key = "${request.method.value} ${request.url.encodedPath.removePrefix("/v2")}"
        val handler = routes[key] ?: error("unexpected request $key (${request.url})")
        handler(request)
    }

    fun json(path: String, body: String, method: String = "GET") {
        routes["$method $path"] = { ok(body) }
    }

    fun requestsTo(path: String) = requests.filter { it.url.encodedPath.removeSuffix("/").endsWith(path) }
}

fun MockRequestHandleScope.ok(body: String, vararg headers: Pair<String, String>): HttpResponseData =
    respond(
        body,
        HttpStatusCode.OK,
        headersOf(*(listOf(HttpHeaders.ContentType to "application/json") + headers).map { it.first to listOf(it.second) }.toTypedArray()),
    )

/** Hand-written data in the shape of WaniKani API v2 responses (not real WaniKani content). */
object WkFixtures {
    const val MNEMONIC = "MNEMONIC-TEXT-MUST-NEVER-BE-STORED"

    fun collection(data: String, nextUrl: String? = null, updatedAt: String = "2026-08-31T12:00:00.000000Z") = """
        {"object":"collection","url":"https://api.wanikani.com/v2/x",
         "pages":{"per_page":500,"next_url":${nextUrl?.let { "\"$it\"" } ?: "null"},"previous_url":null},
         "total_count":0,"data_updated_at":"$updatedAt","data":[$data]}
    """.trimIndent()

    val user = """
        {"object":"user","url":"https://api.wanikani.com/v2/user","data_updated_at":"2026-08-31T12:00:00.000000Z",
         "data":{"id":"u1","username":"tester","level":7,"profile_url":"https://www.wanikani.com/users/tester",
                 "started_at":"2025-01-01T00:00:00.000000Z","current_vacation_started_at":null,
                 "subscription":{"active":true,"type":"recurring","max_level_granted":60,"period_ends_at":null},
                 "preferences":{"lessons_batch_size":5}}}
    """.trimIndent()

    private fun subject(id: Long, type: String, characters: String?, meanings: String, readings: String = "[]") = """
        {"id":$id,"object":"$type","url":"https://api.wanikani.com/v2/subjects/$id","data_updated_at":"2026-01-01T00:00:00.000000Z",
         "data":{"created_at":"2020-01-01T00:00:00.000000Z","level":1,"slug":"s$id","hidden_at":null,
                 "document_url":"https://www.wanikani.com/x/$id","characters":${characters?.let { "\"$it\"" } ?: "null"},
                 "meanings":$meanings,"auxiliary_meanings":[],"readings":$readings,
                 "meaning_mnemonic":"$MNEMONIC","reading_mnemonic":"$MNEMONIC","meaning_hint":"$MNEMONIC",
                 "component_subject_ids":[],"lesson_position":0,"spaced_repetition_system_id":1}}
    """.trimIndent()

    private fun m(meaning: String) = """{"meaning":"$meaning","primary":true,"accepted_answer":true}"""

    val subjects = listOf(
        subject(8, "radical", "一", "[${m("Ground")}]"),
        subject(9, "radical", null, "[${m("Stick Figure")}]"),
        subject(440, "kanji", "一", "[${m("One")}]", """[{"type":"onyomi","primary":true,"accepted_answer":true,"reading":"いち"}]"""),
        subject(2467, "vocabulary", "一つ", "[${m("One Thing")}]", """[{"primary":true,"accepted_answer":true,"reading":"ひとつ"}]"""),
        subject(3000, "kanji", "猫", "[${m("Cat")}]", """[{"type":"kunyomi","primary":true,"accepted_answer":true,"reading":"ねこ"}]"""),
        subject(4000, "vocabulary", "子猫", "[${m("Kitten")}]", """[{"primary":true,"accepted_answer":true,"reading":"こねこ"}]"""),
    ).associateBy { Regex("\"id\":(\\d+)").find(it)!!.groupValues[1].toLong() }

    fun assignment(id: Long, subjectId: Long, type: String, stage: Int, availableAt: String?, burnedAt: String? = null, updatedAt: String = "2026-08-31T12:00:00.000000Z") = """
        {"id":$id,"object":"assignment","url":"https://api.wanikani.com/v2/assignments/$id","data_updated_at":"$updatedAt",
         "data":{"created_at":"2026-01-01T00:00:00.000000Z","subject_id":$subjectId,"subject_type":"$type","srs_stage":$stage,
                 "unlocked_at":"2026-01-01T00:00:00.000000Z","started_at":"2026-01-02T00:00:00.000000Z",
                 "passed_at":null,"burned_at":${burnedAt?.let { "\"$it\"" } ?: "null"},
                 "available_at":${availableAt?.let { "\"$it\"" } ?: "null"},"resurrected_at":null,"hidden":false}}
    """.trimIndent()

    val assignmentsPage1 = listOf(
        assignment(1, 440, "kanji", 5, "2026-09-06T09:00:00.000000Z"),
        assignment(2, 2467, "vocabulary", 1, "2026-09-01T13:00:00.000000Z"),
        assignment(3, 8, "radical", 9, null, burnedAt = "2026-06-01T00:00:00.000000Z"),
    ).joinToString(",")

    val assignmentsPage2 = listOf(
        assignment(4, 3000, "kanji", 7, "2026-09-20T09:00:00.000000Z"),
        assignment(5, 4000, "vocabulary", 3, "2026-09-02T09:00:00.000000Z"),
        assignment(6, 9, "radical", 4, "2026-09-03T09:00:00.000000Z"),
    ).joinToString(",")

    val reviewStatistics = """
        {"id":100,"object":"review_statistic","url":"https://api.wanikani.com/v2/review_statistics/100","data_updated_at":"2026-08-31T12:00:00.000000Z",
         "data":{"created_at":"2026-01-02T00:00:00.000000Z","subject_id":440,"subject_type":"kanji",
                 "meaning_correct":5,"meaning_incorrect":5,"meaning_max_streak":3,"meaning_current_streak":1,
                 "reading_correct":10,"reading_incorrect":0,"reading_max_streak":10,"reading_current_streak":10,
                 "percentage_correct":75,"hidden":false}}
    """.trimIndent()

    val studyMaterials = """
        {"id":200,"object":"study_material","url":"https://api.wanikani.com/v2/study_materials/200","data_updated_at":"2026-08-31T12:00:00.000000Z",
         "data":{"created_at":"2026-01-02T00:00:00.000000Z","subject_id":440,"subject_type":"kanji",
                 "meaning_note":"A single horizontal line","reading_note":null,"meaning_synonyms":["the one"],"hidden":false}}
    """.trimIndent()
}
