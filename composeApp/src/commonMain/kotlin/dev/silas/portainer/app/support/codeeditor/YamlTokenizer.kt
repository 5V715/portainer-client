package dev.silas.portainer.app.support.codeeditor

enum class TokenKind { Key, Scalar, StringLit, Comment, Anchor, Alias, Interpolation, Punct, Plain }

/** A highlighted span `[start, end)` of the text. Whitespace and unknown text get no token. */
data class Token(val start: Int, val end: Int, val kind: TokenKind)

/**
 * Splits [text] into highlight tokens, line by line. It is a highlighter, not a parser: it never fails and
 * makes a best guess on invalid YAML. Tokens are sorted, non-overlapping and never empty.
 *
 * Recognised: mapping keys (plain or quoted, including `<<`), plain scalars, single/double-quoted strings,
 * `# comments` (only at a line start or after whitespace, never inside quotes), `&anchors`, `*aliases`,
 * `!tags` ([TokenKind.Plain]), indicators and flow punctuation, block scalars (`|`/`>`; their lines are
 * strings), and Compose `${VAR}` interpolation inside scalars and strings (`$$` is a literal dollar).
 */
fun tokenizeYaml(text: String): List<Token> {
    val out = ArrayList<Token>(text.length / 8 + 4)
    val scanner = LineScanner(text, out)
    val n = text.length
    var lineStart = 0
    // While >= 0 we are inside a block scalar; its content lines are indented more than this.
    var blockParentIndent = -1
    while (true) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) n else it }
        val contentEnd = if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
        var indentEnd = lineStart
        while (indentEnd < contentEnd && isBlank(text[indentEnd])) indentEnd++
        val indent = indentEnd - lineStart
        val inBlock = blockParentIndent >= 0 && (indentEnd == contentEnd || indent > blockParentIndent)
        if (inBlock) {
            var end = contentEnd
            while (end > indentEnd && isBlank(text[end - 1])) end--
            scanner.emitInterpolated(indentEnd, end, TokenKind.StringLit)
        } else {
            blockParentIndent = if (scanner.scanLine(indentEnd, contentEnd)) indent else -1
        }
        if (lineEnd >= n) break
        lineStart = lineEnd + 1
    }
    return out
}

private fun isBlank(c: Char) = c == ' ' || c == '\t'

private fun isFlowIndicator(c: Char) = c == ',' || c == '[' || c == ']' || c == '{' || c == '}'

private class LineScanner(private val text: String, private val out: MutableList<Token>) {
    private var flowDepth = 0

    private fun emit(start: Int, end: Int, kind: TokenKind) {
        if (end > start) out.add(Token(start, end, kind))
    }

    /** True when the char at [i] ends an indicator: end of line, whitespace, or (in flow) a flow indicator. */
    private fun endsIndicator(i: Int, end: Int) =
        i >= end || isBlank(text[i]) || (flowDepth > 0 && isFlowIndicator(text[i]))

    /** Scans one line's content `[from, end)`; returns true when the line opens a block scalar. */
    fun scanLine(from: Int, end: Int): Boolean {
        var i = from
        var afterSpace = true
        var opensBlock = false
        if (from + 3 <= end && (text.startsWith("---", from) || text.startsWith("...", from)) &&
            (from + 3 == end || isBlank(text[from + 3]))
        ) {
            emit(from, from + 3, TokenKind.Punct)
            i = from + 3
        }
        while (i < end) {
            val c = text[i]
            if (isBlank(c)) {
                i++
                afterSpace = true
                continue
            }
            val start = i
            when {
                c == '#' && afterSpace -> {
                    emit(i, end, TokenKind.Comment)
                    return opensBlock
                }
                (c == '-' || c == '?') && flowDepth == 0 && endsIndicator(i + 1, end) -> {
                    emit(i, i + 1, TokenKind.Punct)
                    i++
                }
                c == ':' && endsIndicator(i + 1, end) -> {
                    emit(i, i + 1, TokenKind.Punct)
                    i++
                }
                c == '[' || c == '{' -> {
                    flowDepth++
                    emit(i, i + 1, TokenKind.Punct)
                    i++
                }
                (c == ']' || c == '}') -> {
                    if (flowDepth > 0) flowDepth--
                    emit(i, i + 1, TokenKind.Punct)
                    i++
                }
                c == ',' && flowDepth > 0 -> {
                    emit(i, i + 1, TokenKind.Punct)
                    i++
                }
                (c == '|' || c == '>') && flowDepth == 0 -> {
                    i++
                    while (i < end && (text[i] == '+' || text[i] == '-' || text[i].isDigit())) i++
                    emit(start, i, TokenKind.Punct)
                    var k = i
                    while (k < end && isBlank(text[k])) k++
                    opensBlock = k == end || (text[k] == '#' && k > i)
                }
                c == '&' || c == '*' || c == '!' -> {
                    i++
                    while (i < end && !isBlank(text[i]) && !isFlowIndicator(text[i])) i++
                    val kind = when (c) {
                        '&' -> TokenKind.Anchor
                        '*' -> TokenKind.Alias
                        else -> TokenKind.Plain
                    }
                    emit(start, i, kind)
                }
                c == '"' || c == '\'' -> {
                    i = quotedEnd(i, end)
                    if (isKeyAt(i, end)) emit(start, i, TokenKind.Key) else emitInterpolated(start, i, TokenKind.StringLit)
                }
                else -> {
                    i = plainEnd(i, end)
                    var trimmed = i
                    while (trimmed > start && isBlank(text[trimmed - 1])) trimmed--
                    if (i < end && text[i] == ':') emit(start, trimmed, TokenKind.Key)
                    else emitInterpolated(start, trimmed, TokenKind.Scalar)
                    if (i == start) i++ // never stall on unexpected input
                }
            }
            afterSpace = false
        }
        return opensBlock
    }

    /** End (exclusive) of the quoted string starting at [i]; an unclosed string runs to the end of the line. */
    private fun quotedEnd(i: Int, end: Int): Int {
        val quote = text[i]
        var j = i + 1
        while (j < end) {
            val ch = text[j]
            if (quote == '"' && ch == '\\') {
                j += 2
            } else if (ch == quote) {
                if (quote == '\'' && j + 1 < end && text[j + 1] == '\'') j += 2 else return j + 1
            } else {
                j++
            }
        }
        return end
    }

    /** True when, after optional blanks, [i] is followed by a `:` mapping indicator. */
    private fun isKeyAt(i: Int, end: Int): Boolean {
        var k = i
        while (k < end && isBlank(text[k])) k++
        return k < end && text[k] == ':' && endsIndicator(k + 1, end)
    }

    /** End of a plain scalar starting at [i]: stops before `: `, ` #` and (in flow) flow indicators. */
    private fun plainEnd(i: Int, end: Int): Int {
        var j = i
        while (j < end) {
            val ch = text[j]
            if (ch == ':' && endsIndicator(j + 1, end)) break
            if (isBlank(ch) && j + 1 < end && text[j + 1] == '#') break
            if (flowDepth > 0 && isFlowIndicator(ch)) break
            j++
        }
        return j
    }

    /** Emits `[start, end)` as [kind], cutting out `${...}` interpolations; `$$` is an escaped dollar. */
    fun emitInterpolated(start: Int, end: Int, kind: TokenKind) {
        var segment = start
        var j = start
        while (j < end - 1) {
            if (text[j] == '$') {
                val next = text[j + 1]
                if (next == '$') {
                    j += 2
                    continue
                }
                if (next == '{') {
                    val close = text.indexOf('}', j + 2)
                    if (close in 0 until end) {
                        emit(segment, j, kind)
                        emit(j, close + 1, TokenKind.Interpolation)
                        j = close + 1
                        segment = j
                        continue
                    }
                }
            }
            j++
        }
        emit(segment, end, kind)
    }
}
