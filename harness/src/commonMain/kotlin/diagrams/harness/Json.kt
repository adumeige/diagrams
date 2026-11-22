package diagrams.harness

import diagrams.model.Value

class JsonException(message: String) : Exception(message)

/** A small JSON reader for what crosses the harness boundary (patches, bundles). */
object Json {
    fun parse(text: String): Value {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw JsonException("unexpected '${text[p.i]}' at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Value {
            ws()
            if (i >= s.length) throw JsonException("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Value.Str(str())
                't' -> lit("true", Value.Bool(true))
                'f' -> lit("false", Value.Bool(false))
                'n' -> lit("null", Value.Null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Value): Value {
            if (!s.startsWith(word, i)) throw JsonException("expected '$word' at $i")
            i += word.length
            return v
        }

        fun num(): Value {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return Value.Num(s.substring(start, i).toDoubleOrNull() ?: throw JsonException("bad number at $start"))
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun obj(): Value {
            i++
            val out = LinkedHashMap<String, Value>()
            ws()
            if (s.getOrNull(i) == '}') {
                i++
                return Value.Map(out)
            }
            while (true) {
                ws()
                if (s.getOrNull(i) != '"') throw JsonException("expected a key at $i")
                val k = str()
                ws()
                if (s.getOrNull(i++) != ':') throw JsonException("expected ':' at ${i - 1}")
                out[k] = value()
                ws()
                when (s.getOrNull(i++)) {
                    ',' -> continue
                    '}' -> return Value.Map(out)
                    else -> throw JsonException("expected ',' or '}' at ${i - 1}")
                }
            }
        }

        fun arr(): Value {
            i++
            val out = mutableListOf<Value>()
            ws()
            if (s.getOrNull(i) == ']') {
                i++
                return Value.List(out)
            }
            while (true) {
                out += value()
                ws()
                when (s.getOrNull(i++)) {
                    ',' -> continue
                    ']' -> return Value.List(out)
                    else -> throw JsonException("expected ',' or ']' at ${i - 1}")
                }
            }
        }
    }
}

/** Patches from JSON: `[{"op": "set", "path": "a.fill", "value": "#ff0000"}, {"op": "add", "parent": "scene", "node": {...}}, {"op": "remove", "path": "a"}]`. */
fun patchesFromJson(text: String): List<Patch> {
    val list = Json.parse(text) as? Value.List ?: throw JsonException("patches are a JSON list")
    return list.items.map { p ->
        val m = p as? Value.Map ?: throw JsonException("a patch is an object")
        fun str(k: String) = (m[k] as? Value.Str)?.value ?: throw JsonException("a '${(m["op"] as? Value.Str)?.value}' patch needs '$k'")
        when ((m["op"] as? Value.Str)?.value) {
            "set" -> Patch.Set(str("path"), m["value"] ?: throw JsonException("a 'set' patch needs 'value'"))
            "add" -> Patch.Add(str("parent"), m["node"] ?: throw JsonException("an 'add' patch needs 'node'"), (m["index"] as? Value.Num)?.value?.toInt())
            "remove" -> Patch.Remove(str("path"))
            else -> throw JsonException("'op' is set, add or remove")
        }
    }
}
