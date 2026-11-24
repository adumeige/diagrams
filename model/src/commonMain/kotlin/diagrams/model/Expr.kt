package diagrams.model

/**
 * The closed expression language of spec section 9.
 *
 * Authored form: a string with a leading `=` (`"=a.right + 2"`); `==` escapes a literal leading `=`.
 * Canonical form: a single-key map naming the operator, whose value is the operand list
 * (`{add: [{ref: a.right}, 2]}`), or `{ref: "a.right"}` for a reference.
 */
sealed interface Expr {
    data class Lit(val value: Value) : Expr
    data class Ref(val path: String) : Expr
    data class Op(val op: String, val args: List<Expr>) : Expr
}

class ExprSyntaxException(message: String, val offset: Int) : Exception(message)
class ExprEvalException(message: String) : Exception(message)

object Exprs {
    val BINARY = setOf("sub", "div", "eq", "ne", "lt", "le", "gt", "ge")
    val VARIADIC = setOf("add", "mul", "and", "or", "min", "max")
    val UNARY = setOf("neg", "not", "abs", "count")

    /** `choose(cond, a, b)`: [a] when the condition holds, else [b]; only the chosen branch is evaluated. */
    const val CHOOSE = "choose"
    val OPERATORS = BINARY + VARIADIC + UNARY + CHOOSE + "ref"

    /** True for an authored expression string (`=...` but not the `==` escape). */
    fun isAuthored(s: String) = s.startsWith("=") && !s.startsWith("==")

    /** True for a literal string written with the `==` escape. */
    fun isEscapedLiteral(s: String) = s.startsWith("==")

    /** The string a literal stands for: `==x` means `=x`. */
    fun unescape(s: String) = if (s.startsWith("==")) s.substring(1) else s

    fun parse(source: String): Expr {
        val body = if (isAuthored(source)) source.substring(1) else source
        return ExprParser(body).parseAll()
    }

    /** Recognizes a canonical expression map; returns null if [v] is not one. Throws on a malformed one. */
    fun fromCanonical(v: Value): Expr? {
        if (v !is Value.Map || v.entries.size != 1) return null
        val (op, arg) = v.entries.entries.first()
        if (op !in OPERATORS) return null
        return convert(v)
    }

    private fun convert(v: Value): Expr = when (v) {
        is Value.Num, is Value.Bool, is Value.Str -> Expr.Lit(v)
        is Value.Map -> {
            if (v.entries.size != 1) throw ExprSyntaxException("an expression map has exactly one operator key", 0)
            val (op, arg) = v.entries.entries.first()
            when {
                op == "ref" -> Expr.Ref(arg.asString ?: throw ExprSyntaxException("'ref' takes a path string", 0))
                op in OPERATORS -> {
                    val args = (arg as? Value.List)?.items ?: throw ExprSyntaxException("'$op' takes a list of operands", 0)
                    checkArity(op, args.size)
                    Expr.Op(op, args.map { convert(it) })
                }
                else -> throw ExprSyntaxException("unknown operator '$op'", 0)
            }
        }
        else -> throw ExprSyntaxException("${v.typeName()} is not allowed inside an expression", 0)
    }

    private fun checkArity(op: String, n: Int) {
        val ok = when (op) {
            in UNARY -> n == 1
            in BINARY -> n == 2
            "min", "max" -> n >= 1
            CHOOSE -> n == 3
            else -> n >= 2
        }
        if (!ok) throw ExprSyntaxException("'$op' does not take $n operand(s)", 0)
    }

    fun toCanonical(e: Expr): Value = when (e) {
        is Expr.Lit -> e.value
        is Expr.Ref -> Value.map("ref" to Value.Str(e.path))
        is Expr.Op -> Value.map(e.op to Value.List(e.args.map { toCanonical(it) }))
    }

    // ---- printer ----

    private fun prec(e: Expr): Int = when (e) {
        is Expr.Op -> when (e.op) {
            "or" -> 1
            "and" -> 2
            "eq", "ne", "lt", "le", "gt", "ge" -> 3
            "add", "sub" -> 4
            "mul", "div" -> 5
            "neg", "not" -> 6
            else -> 7
        }
        else -> 7
    }

    private val SYMBOL = mapOf(
        "or" to "or", "and" to "and", "eq" to "==", "ne" to "!=", "lt" to "<", "le" to "<=", "gt" to ">", "ge" to ">=",
        "add" to "+", "sub" to "-", "mul" to "*", "div" to "/",
    )

    /** Authored spelling, with the leading `=`. */
    fun print(e: Expr): String = "=" + printBare(e)

    fun printBare(e: Expr): String = when (e) {
        is Expr.Lit -> when (val v = e.value) {
            is Value.Str -> "'" + v.value.replace("\\", "\\\\").replace("'", "\\'") + "'"
            is Value.Num -> formatNumber(v.value)
            is Value.Bool -> v.value.toString()
            else -> error("unreachable")
        }
        is Expr.Ref -> e.path
        is Expr.Op -> when (e.op) {
            "min", "max", "abs", "count", CHOOSE -> e.op + e.args.joinToString(", ", "(", ")") { printBare(it) }
            "neg" -> "-" + wrap(e.args[0], 6, strict = true)
            "not" -> "not " + wrap(e.args[0], 6, strict = true)
            else -> {
                val p = prec(e)
                val sym = SYMBOL.getValue(e.op)
                e.args.mapIndexed { i, a ->
                    // left-assoc: right operands of the same precedence need parens; comparisons never chain
                    wrap(a, p, strict = i > 0 || p == 3)
                }.joinToString(" $sym ")
            }
        }
    }

    private fun wrap(e: Expr, parentPrec: Int, strict: Boolean): String {
        val p = prec(e)
        val s = printBare(e)
        return if (p < parentPrec || (strict && p == parentPrec && e is Expr.Op && p != 7)) "($s)" else s
    }

    // ---- partial evaluation (template expansion) ----

    /** Replaces every reference [f] knows with the expression it returns; others stay. */
    fun substitute(e: Expr, f: (String) -> Expr?): Expr = when (e) {
        is Expr.Lit -> e
        is Expr.Ref -> f(e.path) ?: e
        is Expr.Op -> Expr.Op(e.op, e.args.map { substitute(it, f) })
    }

    /** Evaluates every operation whose operands are all literals. A failing operation is left as is. */
    fun fold(e: Expr): Expr {
        if (e !is Expr.Op) return e
        if (e.op == CHOOSE) {
            // a known condition picks its branch, even when the other one cannot be evaluated yet
            val c = fold(e.args[0])
            val v = (c as? Expr.Lit)?.value as? Value.Bool
            if (v != null) return fold(if (v.value) e.args[1] else e.args[2])
            return Expr.Op(e.op, listOf(c, fold(e.args[1]), fold(e.args[2])))
        }
        val args = e.args.map { fold(it) }
        if (args.all { it is Expr.Lit }) {
            try {
                return Expr.Lit(evalOp(e.op, args.map { (it as Expr.Lit).value }))
            } catch (_: ExprEvalException) {
            }
        }
        return Expr.Op(e.op, args)
    }

    /** All reference paths in [e]. */
    fun refs(e: Expr): List<String> = when (e) {
        is Expr.Lit -> emptyList()
        is Expr.Ref -> listOf(e.path)
        is Expr.Op -> e.args.flatMap { refs(it) }
    }

    /**
     * Splits a reference path into steps: `params.items[total].name` gives `params`, `items`,
     * `[total]`, `name`. A step in brackets is a key of a keyed list.
     */
    fun steps(path: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in path) {
            when (c) {
                '.' -> {
                    if (sb.isNotEmpty()) out += sb.toString()
                    sb.clear()
                }
                '[' -> {
                    if (sb.isNotEmpty()) out += sb.toString()
                    sb.clear()
                    sb.append(c)
                }
                ']' -> {
                    sb.append(c)
                    out += sb.toString()
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    // ---- evaluation ----

    fun eval(e: Expr, resolve: (String) -> Value): Value = when (e) {
        is Expr.Lit -> e.value
        is Expr.Ref -> resolve(e.path)
        is Expr.Op -> if (e.op == CHOOSE) {
            eval(if (bool(eval(e.args[0], resolve), e.op)) e.args[1] else e.args[2], resolve)
        } else {
            evalOp(e.op, e.args.map { eval(it, resolve) })
        }
    }

    private fun num(v: Value, op: String): Double =
        (v as? Value.Num)?.value ?: throw ExprEvalException("'$op' expects a number, got ${v.typeName()}")

    private fun bool(v: Value, op: String): Boolean =
        (v as? Value.Bool)?.value ?: throw ExprEvalException("'$op' expects a boolean, got ${v.typeName()}")

    private fun text(v: Value): String = when (v) {
        is Value.Str -> v.value
        is Value.Num -> formatNumber(v.value)
        is Value.Bool -> v.value.toString()
        else -> throw ExprEvalException("cannot concatenate a ${v.typeName()}")
    }

    internal fun evalOp(op: String, a: List<Value>): Value = when (op) {
        "add" -> if (a.any { it is Value.Str }) Value.Str(a.joinToString("") { text(it) }) else Value.Num(a.sumOf { num(it, op) })
        "sub" -> Value.Num(num(a[0], op) - num(a[1], op))
        "mul" -> Value.Num(a.fold(1.0) { acc, v -> acc * num(v, op) })
        "div" -> {
            val d = num(a[1], op)
            if (d == 0.0) throw ExprEvalException("division by zero")
            Value.Num(num(a[0], op) / d)
        }
        "neg" -> Value.Num(-num(a[0], op))
        "abs" -> Value.Num(kotlin.math.abs(num(a[0], op)))
        "count" -> Value.Num(((a[0] as? Value.List) ?: throw ExprEvalException("'count' expects a list, got ${a[0].typeName()}")).items.size.toDouble())
        "min" -> Value.Num(a.minOf { num(it, op) })
        "max" -> Value.Num(a.maxOf { num(it, op) })
        "eq" -> Value.Bool(a[0] == a[1])
        "ne" -> Value.Bool(a[0] != a[1])
        "lt", "le", "gt", "ge" -> {
            val c = compare(a[0], a[1], op)
            Value.Bool(
                when (op) {
                    "lt" -> c < 0
                    "le" -> c <= 0
                    "gt" -> c > 0
                    else -> c >= 0
                },
            )
        }
        "and" -> Value.Bool(a.all { bool(it, op) })
        "or" -> Value.Bool(a.any { bool(it, op) })
        "not" -> Value.Bool(!bool(a[0], op))
        CHOOSE -> if (bool(a[0], op)) a[1] else a[2]
        else -> throw ExprEvalException("unknown operator '$op'")
    }

    private fun compare(x: Value, y: Value, op: String): Int = when {
        x is Value.Num && y is Value.Num -> x.value.compareTo(y.value)
        x is Value.Str && y is Value.Str -> x.value.compareTo(y.value)
        else -> throw ExprEvalException("'$op' compares two numbers or two strings, got ${x.typeName()} and ${y.typeName()}")
    }
}

private class ExprParser(private val src: String) {
    private sealed interface Tok {
        val at: Int
    }

    private data class TNum(val v: Double, override val at: Int) : Tok
    private data class TStr(val v: String, override val at: Int) : Tok
    private data class TId(val v: String, override val at: Int) : Tok
    private data class TSym(val v: String, override val at: Int) : Tok
    private data class TEnd(override val at: Int) : Tok

    private val toks = tokenize()
    private var i = 0

    private fun tokenize(): List<Tok> {
        val out = mutableListOf<Tok>()
        var p = 0
        while (p < src.length) {
            val c = src[p]
            when {
                c == ' ' || c == '\t' -> p++
                c.isDigit() || (c == '.' && p + 1 < src.length && src[p + 1].isDigit()) -> {
                    val start = p
                    while (p < src.length && src[p].isDigit()) p++
                    if (p < src.length && src[p] == '.' && p + 1 < src.length && src[p + 1].isDigit()) {
                        p++
                        while (p < src.length && src[p].isDigit()) p++
                    }
                    if (p < src.length && (src[p] == 'e' || src[p] == 'E')) {
                        val save = p
                        p++
                        if (p < src.length && (src[p] == '+' || src[p] == '-')) p++
                        if (p < src.length && src[p].isDigit()) {
                            while (p < src.length && src[p].isDigit()) p++
                        } else {
                            p = save
                        }
                    }
                    out += TNum(src.substring(start, p).toDouble(), start)
                }
                c == '\'' || c == '"' -> {
                    val start = p
                    p++
                    val sb = StringBuilder()
                    while (true) {
                        if (p >= src.length) throw ExprSyntaxException("unterminated string", start)
                        val ch = src[p]
                        if (ch == '\\' && p + 1 < src.length) {
                            sb.append(src[p + 1])
                            p += 2
                        } else if (ch == c) {
                            p++
                            break
                        } else {
                            sb.append(ch)
                            p++
                        }
                    }
                    out += TStr(sb.toString(), start)
                }
                c.isLetter() || c == '_' -> {
                    val start = p
                    while (p < src.length && (src[p].isLetterOrDigit() || src[p] == '_')) p++
                    out += TId(src.substring(start, p), start)
                }
                else -> {
                    val two = if (p + 1 < src.length) src.substring(p, p + 2) else ""
                    if (two in setOf("==", "!=", "<=", ">=")) {
                        out += TSym(two, p)
                        p += 2
                    } else if (c in "+-*/()<>,.[]") {
                        out += TSym(c.toString(), p)
                        p++
                    } else {
                        throw ExprSyntaxException("unexpected character '$c'", p)
                    }
                }
            }
        }
        out += TEnd(src.length)
        return out
    }

    private fun peek() = toks[i]
    private fun next() = toks[i++]
    private fun isSym(s: String) = (peek() as? TSym)?.v == s
    private fun isKw(s: String) = (peek() as? TId)?.v == s
    private fun expectSym(s: String) {
        if (!isSym(s)) fail("expected '$s'")
        i++
    }

    private fun fail(msg: String): Nothing {
        val t = peek()
        val found = when (t) {
            is TEnd -> "end of expression"
            is TNum -> "number"
            is TStr -> "string"
            is TId -> "'${t.v}'"
            is TSym -> "'${t.v}'"
        }
        throw ExprSyntaxException("$msg, found $found at offset ${t.at}", t.at)
    }

    fun parseAll(): Expr {
        if (peek() is TEnd) fail("empty expression")
        val e = or()
        if (peek() !is TEnd) fail("unexpected token")
        return e
    }

    private fun variadic(op: String, first: Expr, more: () -> Expr?): Expr {
        val args = mutableListOf(first)
        while (true) args += more() ?: break
        return if (args.size == 1) first else Expr.Op(op, args)
    }

    private fun or(): Expr = variadic("or", and()) { if (isKw("or")) { i++; and() } else null }
    private fun and(): Expr = variadic("and", cmp()) { if (isKw("and")) { i++; cmp() } else null }

    private fun cmp(): Expr {
        val left = sum()
        val op = when ((peek() as? TSym)?.v) {
            "==" -> "eq"
            "!=" -> "ne"
            "<" -> "lt"
            "<=" -> "le"
            ">" -> "gt"
            ">=" -> "ge"
            else -> return left
        }
        i++
        return Expr.Op(op, listOf(left, sum()))
    }

    private fun sum(): Expr {
        var e = term()
        while (isSym("+") || isSym("-")) {
            val op = if ((next() as TSym).v == "+") "add" else "sub"
            val r = term()
            e = if (op == "add" && e is Expr.Op && e.op == "add") Expr.Op("add", e.args + r) else Expr.Op(op, listOf(e, r))
        }
        return e
    }

    private fun term(): Expr {
        var e = unary()
        while (isSym("*") || isSym("/")) {
            val op = if ((next() as TSym).v == "*") "mul" else "div"
            val r = unary()
            e = if (op == "mul" && e is Expr.Op && e.op == "mul") Expr.Op("mul", e.args + r) else Expr.Op(op, listOf(e, r))
        }
        return e
    }

    private fun unary(): Expr = when {
        isSym("-") -> {
            i++
            val operand = primary()
            if (operand is Expr.Lit && operand.value is Value.Num) Expr.Lit(Value.Num(-(operand.value as Value.Num).value)) else Expr.Op("neg", listOf(operand))
        }
        isKw("not") -> {
            i++
            Expr.Op("not", listOf(primary()))
        }
        else -> primary()
    }

    private fun primary(): Expr {
        val t = peek()
        return when {
            t is TNum -> {
                i++
                Expr.Lit(Value.Num(t.v))
            }
            t is TStr -> {
                i++
                Expr.Lit(Value.Str(t.v))
            }
            t is TSym && t.v == "(" -> {
                i++
                val e = or()
                expectSym(")")
                e
            }
            t is TId && (t.v == "true" || t.v == "false") -> {
                i++
                Expr.Lit(Value.Bool(t.v == "true"))
            }
            t is TId && t.v in setOf("min", "max", "abs", "count", Exprs.CHOOSE) && (toks[i + 1] as? TSym)?.v == "(" -> {
                i += 2
                val args = mutableListOf(or())
                while (isSym(",")) {
                    i++
                    args += or()
                }
                expectSym(")")
                if ((t.v == "abs" || t.v == "count") && args.size != 1) throw ExprSyntaxException("${t.v} takes one argument", t.at)
                if (t.v == Exprs.CHOOSE && args.size != 3) throw ExprSyntaxException("choose takes a condition and two values", t.at)
                Expr.Op(t.v, args)
            }
            t is TId && t.v !in setOf("and", "or", "not") -> ref()
            else -> fail("expected a value")
        }
    }

    private fun ref(): Expr {
        val sb = StringBuilder((next() as TId).v)
        while (true) {
            when {
                isSym(".") -> {
                    i++
                    val id = peek() as? TId ?: fail("expected a name after '.'")
                    i++
                    sb.append('.').append(id.v)
                }
                isSym("[") -> {
                    i++
                    val id = peek() as? TId ?: fail("expected a key inside '[ ]'")
                    i++
                    expectSym("]")
                    sb.append('[').append(id.v).append(']')
                }
                else -> return Expr.Ref(sb.toString())
            }
        }
    }
}
