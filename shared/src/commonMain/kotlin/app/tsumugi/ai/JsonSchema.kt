package app.tsumugi.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The small JSON Schema subset our prompts use. It renders both as JSON Schema (for OpenAI-compatible
 * `response_format`) and as a llama.cpp GBNF grammar (for on-device constrained decoding), so both engines
 * are held to the same shape.
 *
 * Object properties are ordered; the grammar emits them in that order. Optional properties must come after at
 * least one required one (keeps the grammar's comma handling simple).
 */
sealed interface JsonSchema {
    data class Obj(val properties: List<Pair<String, JsonSchema>>, val required: Set<String> = properties.map { it.first }.toSet()) : JsonSchema {
        init {
            val firstOptional = properties.indexOfFirst { it.first !in required }
            require(firstOptional != 0 || properties.isEmpty()) { "the first property must be required" }
            if (firstOptional > 0) {
                require(properties.drop(firstOptional).none { it.first in required }) { "optional properties must come last" }
            }
        }
    }
    data class Str(val enum: List<String>? = null, val maxLength: Int? = null) : JsonSchema
    data object Num : JsonSchema
    data object Integer : JsonSchema
    data object Bool : JsonSchema
    data class Arr(val items: JsonSchema, val minItems: Int = 0, val maxItems: Int? = null) : JsonSchema

    /** Standard JSON Schema (draft 2020-12 subset), with `additionalProperties: false` for strict mode. */
    fun toJson(): JsonObject = when (this) {
        is Obj -> buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties.associate { (k, v) -> k to v.toJson() }))
            put("required", JsonArray(properties.map { it.first }.filter { it in required }.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
        is Str -> buildJsonObject {
            put("type", "string")
            enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
            maxLength?.let { put("maxLength", it) }
        }
        Num -> buildJsonObject { put("type", "number") }
        Integer -> buildJsonObject { put("type", "integer") }
        Bool -> buildJsonObject { put("type", "boolean") }
        is Arr -> buildJsonObject {
            put("type", "array")
            put("items", items.toJson())
            if (minItems > 0) put("minItems", minItems)
            maxItems?.let { put("maxItems", it) }
        }
    }

    fun toGbnf(): String = Gbnf.from(this)
}

/** JSON Schema → llama.cpp GBNF. The root rule is `root`; shared terminals are emitted once. */
internal object Gbnf {
    private const val STRING = """string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F]{4} ) )* "\"""""
    private const val NUMBER = """number ::= "-"? ( [0-9] | [1-9] [0-9]{0,15} ) ( "." [0-9]+ )? ( [eE] [-+]? [0-9]+ )?"""
    private const val INTEGER = """integer ::= "-"? ( [0-9] | [1-9] [0-9]{0,15} )"""
    private const val BOOLEAN = """boolean ::= "true" | "false""""
    private const val WS = """ws ::= [ \t\n]{0,20}"""

    fun from(schema: JsonSchema): String {
        val rules = LinkedHashMap<String, String>()
        var counter = 0
        fun literal(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun rule(s: JsonSchema): String = when (s) {
            is JsonSchema.Str -> if (s.enum == null) "string" else {
                val name = "enum${counter++}"
                rules[name] = s.enum.joinToString(" | ") { literal("\"" + jsonEscape(it) + "\"") }
                name
            }
            JsonSchema.Num -> "number"
            JsonSchema.Integer -> "integer"
            JsonSchema.Bool -> "boolean"
            is JsonSchema.Arr -> {
                val name = "arr${counter++}"
                val item = rule(s.items)
                val body = when {
                    s.maxItems == 0 -> ""
                    s.minItems == 0 -> {
                        val rest = s.maxItems?.let { "{0,${it - 1}}" } ?: "*"
                        "( $item ( ws \",\" ws $item )$rest )?"
                    }
                    else -> {
                        val min = s.minItems - 1
                        val rest = s.maxItems?.let { "{$min,${it - 1}}" } ?: "{$min,}"
                        "$item ( ws \",\" ws $item )$rest"
                    }
                }
                rules[name] = "\"[\" ws $body ws \"]\""
                name
            }
            is JsonSchema.Obj -> {
                val name = "obj${counter++}"
                val parts = s.properties.mapIndexed { i, (key, value) ->
                    val pair = "${literal("\"" + jsonEscape(key) + "\"")} ws \":\" ws ${rule(value)}"
                    when {
                        i == 0 -> pair
                        key in s.required -> "ws \",\" ws $pair"
                        else -> "( ws \",\" ws $pair )?"
                    }
                }
                rules[name] = "\"{\" ws ${parts.joinToString(" ")} ws \"}\""
                name
            }
        }

        val root = rule(schema)
        return buildString {
            appendLine("root ::= $root")
            rules.forEach { (k, v) -> appendLine("$k ::= $v") }
            appendLine(STRING)
            appendLine(NUMBER)
            appendLine(INTEGER)
            appendLine(BOOLEAN)
            append(WS)
        }
    }

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
