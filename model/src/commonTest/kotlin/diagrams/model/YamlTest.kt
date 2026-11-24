package diagrams.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YamlTest {
    private fun parse(s: String) = Yaml.parse(s).value

    @Test
    fun blockAndFlow() {
        val v = parse(
            """
            format: diagrams/0.1
            viewport: {width: 1800, height: 1300}
            scene:
              layers: [edges, classes, notes]
              children:
                - shape: rect     # a comment
                  id: box
                  at: {x: 10, y: -2.5}
                - use: uml.class
                  attributes: [kickoff: DateTime, competition: String]
                  operations:
                    - winner: "(): Team?"
            """.trimIndent(),
        )
        val m = v.asMap!!
        assertEquals(Value.Str("diagrams/0.1"), m["format"])
        assertEquals(Value.map("width" to Value.num(1800), "height" to Value.num(1300)), m["viewport"])
        val children = m["scene"]!!.asMap!!["children"]!!.asList!!.items
        assertEquals(Value.num(-2.5), children[0].asMap!!["at"]!!.asMap!!["y"])
        assertEquals(
            Value.list(Value.map("kickoff" to Value.str("DateTime")), Value.map("competition" to Value.str("String"))),
            children[1].asMap!!["attributes"],
        )
        assertEquals(Value.str("(): Team?"), children[1].asMap!!["operations"]!!.asList!!.items[0].asMap!!["winner"])
    }

    @Test
    fun sequenceAtKeyIndent() {
        val v = parse("a:\n- 1\n- 2\nb: x\n")
        assertEquals(Value.map("a" to Value.list(Value.num(1), Value.num(2)), "b" to Value.str("x")), v)
    }

    @Test
    fun positions() {
        val doc = Yaml.parse("a:\n  b: [1, 2]\n  c: hi\n")
        assertEquals(SourcePos(2, 6), doc.sourceMap.positions[DataPath.parse("/a/b")])
        assertEquals(SourcePos(2, 10), doc.sourceMap.positions[DataPath.parse("/a/b/1")])
        assertEquals(SourcePos(3, 6), doc.sourceMap.positions[DataPath.parse("/a/c")])
    }

    @Test
    fun blockScalars() {
        assertEquals(Value.map("t" to Value.str("one\ntwo\n")), parse("t: |\n  one\n  two\n"))
        assertEquals(Value.map("t" to Value.str("one two")), parse("t: >-\n  one\n  two\n"))
    }

    @Test
    fun strictSubset() {
        for (bad in listOf("a: yes", "a: 012", "a: &x 1", "a: *x", "a: !tag x", "<<: {a: 1}", "a: 1\na: 2", "a: b: c", "a: ~", "a: 1.", "a: 0x10")) {
            assertFailsWith<YamlException>(bad) { parse(bad) }
        }
        assertEquals(Value.map("a" to Value.Null), parse("a:"))
        assertEquals(Value.map("a" to Value.str("#c0392b")), parse("a: \"#c0392b\""))
    }

    @Test
    fun jsonIsAccepted() {
        assertEquals(
            Value.map("a" to Value.list(Value.num(1), Value.num(2.5)), "b" to Value.str("x\ny")),
            parse("{\"a\": [1, 2.5], \"b\": \"x\\ny\"}"),
        )
    }

    @Test
    fun writerRoundTrips() {
        val src = Value.map(
            "format" to Value.str("diagrams/0.1"),
            "n" to Value.num(1.5),
            "s" to Value.list(Value.str("yes"), Value.str("012"), Value.str("a: b"), Value.str("=x + 1"), Value.str("")),
            "nested" to Value.list(Value.map("id" to Value.str("a"), "children" to Value.list(Value.map("k" to Value.list(Value.num(1)))))),
        )
        assertEquals(src, parse(YamlWriter.write(src)))
        assertEquals(src, parse(JsonWriter.write(src)))
    }

    @Test
    fun numbers() {
        assertEquals("12", formatNumber(12.0))
        assertEquals("-0.5", formatNumber(-0.5))
        assertEquals("0.333333", formatNumber(1.0 / 3))
        assertEquals("0", formatNumber(-0.0000001))
    }
}

class ExprTest {
    @Test
    fun parsesToCanonical() {
        assertEquals(
            Value.map("add" to Value.list(Value.map("ref" to Value.str("a.right")), Value.num(2))),
            Exprs.toCanonical(Exprs.parse("=a.right + 2")),
        )
        assertEquals(
            Value.map("add" to Value.list(Value.map("ref" to Value.str("attr.key")), Value.str(": "), Value.map("ref" to Value.str("attr.value")))),
            Exprs.toCanonical(Exprs.parse("=attr.key + ': ' + attr.value")),
        )
    }

    @Test
    fun printsBack() {
        for (s in listOf("=a.right + 2", "=(a + b) * c", "=a - (b - c)", "=min(a, 2) >= 3 and not done", "=order.attributes[total].width / 2", "=-x", "=a * -3")) {
            val e = Exprs.parse(s)
            assertEquals(s, Exprs.print(e))
            assertEquals(e, Exprs.fromCanonical(Exprs.toCanonical(e)) ?: e)
        }
    }

    @Test
    fun evaluates() {
        val env = mapOf("w" to Value.num(100), "name" to Value.str("Order"))
        fun ev(s: String) = Exprs.eval(Exprs.parse(s)) { env[it] ?: throw ExprEvalException("no $it") }
        assertEquals(Value.num(48), ev("=w / 2 - 2"))
        assertEquals(Value.str("Order: 100"), ev("=name + ': ' + w"))
        assertEquals(Value.Bool(true), ev("=max(w, 3) == 100 or false"))
    }

    @Test
    fun choosesOneBranch() {
        val env = mapOf("dashed" to Value.Bool(true), "w" to Value.num(10))
        fun ev(s: String) = Exprs.eval(Exprs.parse(s)) { env[it] ?: throw ExprEvalException("no $it") }
        assertEquals(Value.str("open_arrow"), ev("=choose(dashed, 'open_arrow', 'arrow')"))
        assertEquals(Value.num(20), ev("=choose(w > 20, missing, w * 2)"), "the branch not taken is not evaluated")
        val e = Exprs.parse("=choose(dashed, x.width, 4)")
        assertEquals("=choose(dashed, x.width, 4)", Exprs.print(e))
        assertEquals(e, Exprs.fromCanonical(Exprs.toCanonical(e)))
        // a known condition folds away the other branch, even one that refers to geometry
        val folded = Exprs.fold(Exprs.substitute(e) { if (it == "dashed") Expr.Lit(Value.Bool(false)) else null })
        assertEquals(Expr.Lit(Value.num(4)), folded)
        assertFailsWith<ExprSyntaxException> { Exprs.parse("=choose(a, b)") }
        val rules = Exprs.substitute(Exprs.parse("=240 / count(params.rules)")) { if (it == "params.rules") Expr.Lit(Value.list(Value.str("a"), Value.str("b"), Value.str("c"))) else null }
        assertEquals(Expr.Lit(Value.num(80)), Exprs.fold(rules))
    }

    @Test
    fun rejects() {
        for (s in listOf("=", "=a +", "=1 < 2 < 3", "=f(1)", "=a..b", "='x")) {
            assertFailsWith<ExprSyntaxException>(s) { Exprs.parse(s) }
        }
    }
}
