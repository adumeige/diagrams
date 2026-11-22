package diagrams.model

/**
 * A parser for the strict YAML subset of spec section 3:
 * YAML 1.2 core types only, no anchors, aliases, merge keys or tags, no duplicate keys,
 * only `true`/`false` as booleans, and plain scalars that could read as another type are rejected.
 *
 * JSON is accepted as well, since every JSON document is a flow-style document of this subset.
 * The parser records the line and column of every value, keyed by its [DataPath].
 */
class YamlException(val code: String, message: String, val line: Int, val column: Int) :
    Exception("$message (line $line, column $column)") {
    val bareMessage: String = message
}

class ParsedDocument(val value: Value, val sourceMap: SourceMap)

object Yaml {
    fun parse(text: String, file: String? = null): ParsedDocument {
        val p = YamlParser(text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n'))
        val v = p.parseDocument()
        return ParsedDocument(v, SourceMap(file, p.positions))
    }

    /** Parses and turns a syntax error into a diagnostic instead of an exception. */
    fun parseOrDiagnose(text: String, file: String?, sink: DiagnosticSink): ParsedDocument? =
        try {
            parse(text, file)
        } catch (e: YamlException) {
            sink.add(Diagnostic(e.code, Severity.ERROR, "", e.bareMessage, Diagnostic.Source(file, e.line, e.column)))
            null
        }

    private val strictNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?")
    private val ambiguousWords = setOf(
        "y", "Y", "yes", "Yes", "YES", "n", "N", "no", "No", "NO",
        "True", "TRUE", "False", "FALSE", "on", "On", "ON", "off", "Off", "OFF",
        "Null", "NULL", "~",
    )
    // anchored and grouped by hand: on JS, `matches` does not backtrack into a later alternative
    private val ambiguousNumber = Regex(
        "^(?:[-+]?(\\.[0-9_]+|[0-9][0-9_]*(\\.[0-9_]*)?|0x[0-9a-fA-F_]+|0o[0-7_]+|0b[01_]+)([eE][-+]?[0-9]+)?" +
            "|[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)|[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+(\\.[0-9_]*)?)$",
    )

    fun isStrictNumber(s: String) = strictNumber.matches(s)

    /** True when a plain scalar would not read back as the same string. */
    fun needsQuoting(s: String): Boolean =
        s == "true" || s == "false" || s == "null" || strictNumber.matches(s) || isAmbiguous(s)

    fun isAmbiguous(s: String) = s in ambiguousWords || ambiguousNumber.containsMatchIn(s)

    internal fun resolvePlain(s: String, line: Int, column: Int): Value = when {
        s == "true" -> Value.Bool(true)
        s == "false" -> Value.Bool(false)
        s == "null" -> Value.Null
        strictNumber.matches(s) -> Value.Num(s.toDouble())
        isAmbiguous(s) -> throw YamlException(
            Codes.YAML_SUBSET,
            "plain scalar '$s' could be read as a boolean, number or null; quote it",
            line, column,
        )
        else -> Value.Str(s)
    }
}

private class YamlParser(private val text: String) {
    private var pos = 0
    private val n = text.length
    val positions = LinkedHashMap<DataPath, SourcePos>()

    private fun lineStart(p: Int): Int = lineStarts[line(minOf(p, n)) - 1]

    private val lineStarts: IntArray = buildList {
        add(0)
        for (i in text.indices) if (text[i] == '\n') add(i + 1)
    }.toIntArray()

    private fun line(p: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= p) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    private fun col(p: Int): Int = p - lineStart(p)

    private fun err(message: String, at: Int = pos, code: String = Codes.YAML_SYNTAX): Nothing =
        throw YamlException(code, message, line(at), col(at) + 1)

    private fun record(path: DataPath, at: Int) {
        positions[path] = SourcePos(line(at), col(at) + 1)
    }

    private fun peek(off: Int = 0): Char = if (pos + off < n) text[pos + off] else '\u0000'
    private fun eol(p: Int = pos) = p >= n || text[p] == '\n'
    private fun isWs(c: Char) = c == ' ' || c == '\t'
    private fun isWsOrEnd(p: Int) = p >= n || text[p] == ' ' || text[p] == '\t' || text[p] == '\n'

    /**
     * Moves to the next content character, skipping blank lines and comments.
     * Returns the 0-based column of that character, or -1 at end of input.
     */
    private fun skipToContent(): Int {
        while (true) {
            var i = pos
            while (i < n && isWs(text[i])) i++
            if (i >= n) {
                pos = n
                return -1
            }
            when (text[i]) {
                '\n' -> pos = i + 1
                '#' -> {
                    while (i < n && text[i] != '\n') i++
                    pos = i
                }
                else -> {
                    val ls = lineStart(i)
                    val indentation = text.substring(ls, i)
                    if (indentation.all { isWs(it) } && '\t' in indentation) {
                        err("tab characters are not allowed in indentation", ls + indentation.indexOf('\t'))
                    }
                    pos = i
                    return i - ls
                }
            }
        }
    }


    /** Consumes trailing whitespace, an optional comment and the newline. */
    private fun finishLine() {
        while (pos < n && isWs(text[pos])) pos++
        if (pos < n && text[pos] == '#') {
            if (pos > 0 && !isWs(text[pos - 1]) && text[pos - 1] != '\n') err("a comment must be preceded by whitespace")
            while (pos < n && text[pos] != '\n') pos++
        }
        if (pos < n && text[pos] != '\n') err("unexpected content '${text.substring(pos, minOf(n, pos + 20)).substringBefore('\n')}'")
        if (pos < n) pos++
    }

    private fun isDocMarker(p: Int = pos): Boolean =
        col(p) == 0 && (text.startsWith("---", p) || text.startsWith("...", p)) && isWsOrEnd(p + 3)

    private fun startsDash(p: Int = pos) = p < n && text[p] == '-' && isWsOrEnd(p + 1)

    fun parseDocument(): Value {
        var ind = skipToContent()
        if (ind == -1) return Value.Null
        if (peek() == '%') err("directives are not part of the strict YAML subset", code = Codes.YAML_SUBSET)
        if (ind == 0 && text.startsWith("---", pos) && isWsOrEnd(pos + 3)) {
            pos += 3
            while (pos < n && isWs(text[pos])) pos++
            if (eol() || peek() == '#') {
                finishLine()
                ind = skipToContent()
            } else {
                ind = col(pos)
            }
        }
        val v = if (ind == -1 || isDocMarker()) {
            record(DataPath.ROOT, pos)
            Value.Null
        } else {
            parseNode(ind, -1, DataPath.ROOT)
        }
        ind = skipToContent()
        if (ind != -1) {
            if (ind == 0 && text.startsWith("...", pos) && isWsOrEnd(pos + 3)) {
                pos += 3
                finishLine()
                if (skipToContent() != -1) err("content after the end of the document")
            } else if (ind == 0 && text.startsWith("---", pos)) {
                err("a file holds exactly one document", code = Codes.YAML_SUBSET)
            } else {
                err("unexpected content at this indentation")
            }
        }
        return v
    }

    /** Parses the node whose first character is at [pos], at column [column]; [parentIndent] is the enclosing block's column. */
    private fun parseNode(column: Int, parentIndent: Int, path: DataPath): Value {
        if (isDocMarker()) err("unexpected document marker")
        return when {
            startsDash() -> parseBlockSeq(column, path)
            isMappingKeyAhead() -> parseBlockMap(column, path)
            else -> parseInline(path, parentIndent, column)
        }
    }

    private fun parseBlockSeq(column: Int, path: DataPath): Value {
        val items = mutableListOf<Value>()
        record(path, pos)
        while (true) {
            val dash = pos
            val itemPath = path.index(items.size)
            pos++
            while (pos < n && isWs(text[pos])) pos++
            val item = if (eol() || peek() == '#') {
                finishLine()
                val ind = skipToContent()
                if (ind > column && !isDocMarker()) {
                    parseNode(ind, column, itemPath)
                } else {
                    record(itemPath, dash)
                    Value.Null
                }
            } else {
                parseNode(col(pos), column, itemPath)
            }
            items += item
            val ind = skipToContent()
            if (ind == -1 || ind < column || isDocMarker()) break
            if (ind > column) err("unexpected indentation; expected a '- ' entry at column ${column + 1}")
            if (!startsDash()) break
        }
        return Value.List(items)
    }

    private fun parseBlockMap(column: Int, path: DataPath): Value {
        val entries = LinkedHashMap<String, Value>()
        record(path, pos)
        while (true) {
            val keyPos = pos
            val key = parseBlockKey()
            if (key == "<<") err("merge keys are not part of the strict YAML subset", keyPos, Codes.YAML_SUBSET)
            if (key in entries) err("duplicate key '$key'", keyPos)
            val childPath = path.key(key)
            while (pos < n && isWs(text[pos])) pos++
            val value = if (eol() || peek() == '#') {
                finishLine()
                val ind = skipToContent()
                when {
                    ind > column && !isDocMarker() -> parseNode(ind, column, childPath)
                    ind == column && startsDash() -> parseBlockSeq(column, childPath)
                    else -> {
                        record(childPath, keyPos)
                        Value.Null
                    }
                }
            } else {
                if (startsDash()) err("a block sequence cannot start on the same line as its key")
                parseInline(childPath, column, col(pos))
            }
            entries[key] = value
            val ind = skipToContent()
            if (ind == -1 || ind < column || isDocMarker()) break
            if (ind > column) err("unexpected indentation; expected a key at column ${column + 1}")
            if (!isMappingKeyAhead()) err("expected a 'key: value' entry")
        }
        return Value.Map(entries)
    }

    /** True if the current line, from [pos], starts with `key:` followed by whitespace or end of line. */
    private fun isMappingKeyAhead(): Boolean {
        var i = pos
        if (i >= n) return false
        val c = text[i]
        if (c == '"' || c == '\'') {
            i = skipQuotedOnLine(i) ?: return false
            while (i < n && isWs(text[i])) i++
            return i < n && text[i] == ':' && isWsOrEnd(i + 1)
        }
        if (c in "[{|>#" || startsDash(i)) return false
        if (c == '?' && isWsOrEnd(i + 1)) err("complex keys are not part of the strict YAML subset", i, Codes.YAML_SUBSET)
        while (i < n && text[i] != '\n') {
            if (text[i] == '#' && i > pos && isWs(text[i - 1])) return false
            if (text[i] == ':' && isWsOrEnd(i + 1)) return true
            i++
        }
        return false
    }

    private fun skipQuotedOnLine(start: Int): Int? {
        val q = text[start]
        var i = start + 1
        while (i < n && text[i] != '\n') {
            if (q == '"' && text[i] == '\\') {
                i += 2
                continue
            }
            if (text[i] == q) {
                if (q == '\'' && i + 1 < n && text[i + 1] == '\'') {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return null
    }

    private fun parseBlockKey(): String {
        val c = peek()
        val key = if (c == '"' || c == '\'') {
            val start = pos
            val k = parseQuoted(multiLine = false)
            while (pos < n && isWs(text[pos])) pos++
            if (peek() != ':') err("expected ':' after key", start)
            k
        } else {
            checkIndicator()
            val start = pos
            while (!(text[pos] == ':' && isWsOrEnd(pos + 1))) pos++
            text.substring(start, pos).trimEnd()
        }
        pos++ // ':'
        return key
    }

    private fun checkIndicator() {
        when (peek()) {
            '&' -> err("anchors are not part of the strict YAML subset", code = Codes.YAML_SUBSET)
            '*' -> err("aliases are not part of the strict YAML subset", code = Codes.YAML_SUBSET)
            '!' -> err("tags are not part of the strict YAML subset", code = Codes.YAML_SUBSET)
            '%', '@', '`' -> err("'${peek()}' is reserved and cannot start a plain scalar")
            '?' -> if (isWsOrEnd(pos + 1)) err("complex keys are not part of the strict YAML subset", code = Codes.YAML_SUBSET)
        }
    }

    /** A value that starts on the current line: flow collection, quoted or plain scalar, or block scalar. */
    private fun parseInline(path: DataPath, parentIndent: Int, column: Int): Value {
        val start = pos
        record(path, start)
        return when (peek()) {
            '[', '{' -> {
                val v = parseFlowNode(path)
                finishLine()
                v
            }
            '"', '\'' -> {
                val s = parseQuoted(multiLine = true)
                finishLine()
                Value.Str(s)
            }
            '|', '>' -> parseBlockScalar(parentIndent)
            else -> {
                checkIndicator()
                val s = readPlainBlock()
                if (s.isEmpty()) err("expected a value")
                finishLine()
                Yaml.resolvePlain(s, line(start), col(start) + 1)
            }
        }
    }

    private fun readPlainBlock(): String {
        val start = pos
        while (pos < n && text[pos] != '\n') {
            if (text[pos] == '#' && pos > start && isWs(text[pos - 1])) break
            if (text[pos] == ':' && isWsOrEnd(pos + 1)) err("mapping values are not allowed here; quote the string if it contains ': '")
            pos++
        }
        return text.substring(start, pos).trimEnd()
    }

    // ---- quoted scalars ----

    private fun parseQuoted(multiLine: Boolean): String {
        val q = text[pos]
        val start = pos
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= n) err("unterminated quoted string", start)
            val c = text[pos]
            when {
                c == '\n' -> {
                    if (!multiLine) err("a quoted key must fit on one line", start)
                    // fold: trailing spaces dropped, one newline -> space, k empty lines -> k newlines
                    while (sb.isNotEmpty() && isWs(sb.last())) sb.setLength(sb.length - 1)
                    var empties = 0
                    pos++
                    while (true) {
                        while (pos < n && isWs(text[pos])) pos++
                        if (pos < n && text[pos] == '\n') {
                            empties++
                            pos++
                        } else {
                            break
                        }
                    }
                    if (empties == 0) sb.append(' ') else repeat(empties) { sb.append('\n') }
                }
                c == q && q == '\'' && peek(1) == '\'' -> {
                    sb.append('\'')
                    pos += 2
                }
                c == q -> {
                    pos++
                    return sb.toString()
                }
                c == '\\' && q == '"' -> {
                    pos++
                    val e = peek()
                    pos++
                    when (e) {
                        '0' -> sb.append('\u0000')
                        'a' -> sb.append('\u0007')
                        'b' -> sb.append('\b')
                        't', '\t' -> sb.append('\t')
                        'n' -> sb.append('\n')
                        'v' -> sb.append('\u000B')
                        'f' -> sb.append('\u000C')
                        'r' -> sb.append('\r')
                        'e' -> sb.append('\u001B')
                        ' ' -> sb.append(' ')
                        '"' -> sb.append('"')
                        '/' -> sb.append('/')
                        '\\' -> sb.append('\\')
                        'N' -> sb.append('\u0085')
                        '_' -> sb.append(' ')
                        'x' -> sb.append(hex(2))
                        'u' -> sb.append(hex(4))
                        'U' -> sb.append(hex(8))
                        '\n' -> while (pos < n && isWs(text[pos])) pos++
                        else -> err("unknown escape '\\$e'", pos - 2)
                    }
                }
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    private fun hex(digits: Int): String {
        if (pos + digits > n) err("truncated escape")
        val h = text.substring(pos, pos + digits)
        val cp = h.toIntOrNull(16) ?: err("invalid escape digits '$h'")
        pos += digits
        return if (cp < 0x10000) {
            cp.toChar().toString()
        } else {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }

    // ---- block scalars ----

    private fun parseBlockScalar(parentIndent: Int): Value {
        val literal = text[pos] == '|'
        pos++
        var chomp = 'c'
        var explicitIndent = -1
        repeat(2) {
            when {
                peek() == '-' || peek() == '+' -> {
                    chomp = peek()
                    pos++
                }
                peek() in '1'..'9' -> {
                    explicitIndent = peek() - '0'
                    pos++
                }
            }
        }
        finishLine()
        val baseIndent = maxOf(parentIndent, 0)
        // collect raw lines
        val lines = mutableListOf<String>()
        var contentIndent = if (explicitIndent > 0) (if (parentIndent < 0) 0 else parentIndent) + explicitIndent else -1
        while (pos < n) {
            val ls = pos
            var i = ls
            while (i < n && text[i] == ' ') i++
            val lineEnd = text.indexOf('\n', ls).let { if (it < 0) n else it }
            val blank = text.substring(i, lineEnd).isBlank()
            val indent = i - ls
            if (!blank) {
                if (contentIndent < 0) {
                    if (indent <= baseIndent && parentIndent >= 0) break
                    contentIndent = indent
                }
                if (indent < contentIndent) break
            }
            lines += if (blank) {
                if (contentIndent >= 0 && lineEnd - ls > contentIndent) text.substring(ls + contentIndent, lineEnd) else ""
            } else {
                text.substring(ls + contentIndent, lineEnd)
            }
            pos = if (lineEnd < n) lineEnd + 1 else n
        }
        // trailing blank lines belong to chomping
        var trailing = 0
        while (lines.isNotEmpty() && lines.last().isBlank()) {
            lines.removeAt(lines.size - 1)
            trailing++
        }
        val body = if (literal) lines.joinToString("\n") else fold(lines)
        val result = when {
            lines.isEmpty() -> if (chomp == '+') "\n".repeat(trailing) else ""
            chomp == '-' -> body
            chomp == '+' -> body + "\n" + "\n".repeat(trailing)
            else -> body + "\n"
        }
        return Value.Str(result)
    }

    private fun fold(lines: List<String>): String {
        val sb = StringBuilder()
        var i = 0
        var prevText: String? = null
        while (i < lines.size) {
            var empties = 0
            while (i < lines.size && lines[i].isEmpty()) {
                empties++
                i++
            }
            if (i >= lines.size) break
            val cur = lines[i]
            if (prevText != null) {
                val moreIndented = cur.startsWith(" ") || cur.startsWith("\t") || prevText.startsWith(" ")
                if (empties == 0) sb.append(if (moreIndented) "\n" else " ") else sb.append("\n".repeat(if (moreIndented) empties + 1 else empties))
            } else {
                sb.append("\n".repeat(empties))
            }
            sb.append(cur)
            prevText = cur
            i++
        }
        return sb.toString()
    }

    // ---- flow collections ----

    private fun skipFlowWs() {
        while (pos < n) {
            val c = text[pos]
            if (isWs(c) || c == '\n') {
                pos++
            } else if (c == '#') {
                if (pos > 0 && !isWs(text[pos - 1]) && text[pos - 1] != '\n') err("a comment must be preceded by whitespace")
                while (pos < n && text[pos] != '\n') pos++
            } else {
                break
            }
        }
    }

    private class RawScalar(val text: String, val quoted: Boolean, val at: Int)

    private fun parseFlowNode(path: DataPath): Value {
        skipFlowWs()
        record(path, pos)
        return when (peek()) {
            '[' -> parseFlowSeq(path)
            '{' -> parseFlowMap(path)
            else -> resolve(readFlowScalar())
        }
    }

    private fun resolve(r: RawScalar): Value =
        if (r.quoted) Value.Str(r.text) else Yaml.resolvePlain(r.text, line(r.at), col(r.at) + 1)

    private fun readFlowScalar(): RawScalar {
        val at = pos
        if (pos >= n) err("unexpected end of input in a flow collection")
        val c = peek()
        if (c == '"' || c == '\'') return RawScalar(parseQuoted(multiLine = true), true, at)
        if (c == ',' || c == ']' || c == '}') err("expected a value")
        checkIndicator()
        if (c == '|' || c == '>') err("block scalars are not allowed inside a flow collection")
        if (startsDash()) err("block sequences are not allowed inside a flow collection")
        while (pos < n) {
            val ch = text[pos]
            if (ch == '\n' || ch == ',' || ch == '[' || ch == ']' || ch == '{' || ch == '}') break
            if (ch == ':' && (isWsOrEnd(pos + 1) || text[pos + 1] in ",[]{}")) break
            if (ch == '#' && pos > at && isWs(text[pos - 1])) break
            pos++
        }
        val s = text.substring(at, pos).trimEnd()
        if (s.isEmpty()) err("expected a value", at)
        return RawScalar(s, false, at)
    }

    private fun parseFlowSeq(path: DataPath): Value {
        val open = pos
        pos++ // [
        val items = mutableListOf<Value>()
        while (true) {
            skipFlowWs()
            if (pos >= n) err("unterminated flow sequence", open)
            if (peek() == ']') {
                pos++
                break
            }
            val itemPath = path.index(items.size)
            val itemStart = pos
            record(itemPath, itemStart)
            val item: Value = when (peek()) {
                '[' -> parseFlowSeq(itemPath)
                '{' -> parseFlowMap(itemPath)
                else -> {
                    val raw = readFlowScalar()
                    skipFlowWs()
                    if (peek() == ':') {
                        // single-pair map: `[kickoff: DateTime]`
                        pos++
                        skipFlowWs()
                        val valuePath = itemPath.key(raw.text)
                        val v = if (peek() == ',' || peek() == ']') {
                            record(valuePath, pos)
                            Value.Null
                        } else {
                            parseFlowNode(valuePath)
                        }
                        Value.Map(linkedMapOf(raw.text to v))
                    } else {
                        resolve(raw)
                    }
                }
            }
            items += item
            skipFlowWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    break
                }
                else -> err("expected ',' or ']' in a flow sequence")
            }
        }
        return Value.List(items)
    }

    private fun parseFlowMap(path: DataPath): Value {
        val open = pos
        pos++ // {
        val entries = LinkedHashMap<String, Value>()
        while (true) {
            skipFlowWs()
            if (pos >= n) err("unterminated flow mapping", open)
            if (peek() == '}') {
                pos++
                break
            }
            if (peek() == '[' || peek() == '{') err("complex keys are not part of the strict YAML subset", code = Codes.YAML_SUBSET)
            val keyAt = pos
            val raw = readFlowScalar()
            if (!raw.quoted && raw.text == "<<") err("merge keys are not part of the strict YAML subset", keyAt, Codes.YAML_SUBSET)
            if (raw.text in entries) err("duplicate key '${raw.text}'", keyAt)
            val childPath = path.key(raw.text)
            skipFlowWs()
            val v = if (peek() == ':') {
                pos++
                skipFlowWs()
                if (peek() == ',' || peek() == '}') {
                    record(childPath, pos)
                    Value.Null
                } else {
                    parseFlowNode(childPath)
                }
            } else {
                record(childPath, keyAt)
                Value.Null
            }
            entries[raw.text] = v
            skipFlowWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    break
                }
                else -> err("expected ',' or '}' in a flow mapping")
            }
        }
        return Value.Map(entries)
    }
}
