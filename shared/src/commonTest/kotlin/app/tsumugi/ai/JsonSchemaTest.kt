package app.tsumugi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonSchemaTest {
    private val schema = JsonSchema.Obj(
        listOf(
            "ok" to JsonSchema.Bool,
            "kind" to JsonSchema.Str(enum = listOf("a", "b\"c")),
            "items" to JsonSchema.Arr(JsonSchema.Integer, minItems = 1, maxItems = 3),
            "note" to JsonSchema.Str(maxLength = 10),
        ),
        required = setOf("ok", "kind", "items"),
    )

    @Test
    fun rendersJsonSchema() {
        assertEquals(
            """{"type":"object","properties":{"ok":{"type":"boolean"},"kind":{"type":"string","enum":["a","b\"c"]},""" +
                """"items":{"type":"array","items":{"type":"integer"},"minItems":1,"maxItems":3},""" +
                """"note":{"type":"string","maxLength":10}},"required":["ok","kind","items"],"additionalProperties":false}""",
            schema.toJson().toString(),
        )
    }

    @Test
    fun rendersGbnf() {
        val expected = listOf(
            """root ::= obj0""",
            """enum1 ::= "\"a\"" | "\"b\\\"c\""""",
            """arr2 ::= "[" ws integer ( ws "," ws integer ){0,2} ws "]"""",
            """obj0 ::= "{" ws "\"ok\"" ws ":" ws boolean ws "," ws "\"kind\"" ws ":" ws enum1 ws "," ws "\"items\"" ws ":" ws arr2 ( ws "," ws "\"note\"" ws ":" ws string )? ws "}"""",
        )
        val lines = schema.toGbnf().lines()
        assertEquals(expected, lines.take(4))
        assertTrue(lines.any { it.startsWith("string ::= ") })
        assertTrue(lines.any { it.startsWith("integer ::= ") })
        assertEquals("ws ::= [ \\t\\n]{0,20}", lines.last())
    }

    @Test
    fun optionalArraysAndFixedSizes() {
        val g = JsonSchema.Arr(JsonSchema.Str(), minItems = 0, maxItems = null).toGbnf().lines()
        assertEquals("""arr0 ::= "[" ws ( string ( ws "," ws string )* )? ws "]"""", g[1])
        val four = JsonSchema.Arr(JsonSchema.Str(), minItems = 4, maxItems = 4).toGbnf().lines()
        assertEquals("""arr0 ::= "[" ws string ( ws "," ws string ){3,3} ws "]"""", four[1])
    }

    @Test
    fun rejectsOptionalBeforeRequired() {
        assertFailsWith<IllegalArgumentException> {
            JsonSchema.Obj(listOf("a" to JsonSchema.Bool, "b" to JsonSchema.Bool, "c" to JsonSchema.Bool), required = setOf("a", "c"))
        }
        assertFailsWith<IllegalArgumentException> {
            JsonSchema.Obj(listOf("a" to JsonSchema.Bool), required = emptySet())
        }
    }
}
