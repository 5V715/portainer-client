package dev.silas.portainer.core.editor

/** Characters an argument may contain and still be written without quotes. */
private fun isSafe(c: Char) = c.isLetterOrDigit() || c in "-_./:=@%+,"

/**
 * Splits a shell-like single line into arguments: whitespace separates; `'…'` is literal; inside `"…"`
 * a backslash escapes only `"` and `\`; outside quotes a backslash escapes any character. `''` / `""` give
 * an empty argument. An unterminated quote runs to the end of the line. No variable or glob expansion.
 */
fun splitCommandLine(s: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var inArg = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.isWhitespace() -> {
                if (inArg) args += current.toString()
                current.clear()
                inArg = false
            }
            c == '\'' -> {
                inArg = true
                val end = s.indexOf('\'', i + 1).let { if (it < 0) s.length else it }
                current.append(s, i + 1, end)
                i = end
            }
            c == '"' -> {
                inArg = true
                i++
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.length && (s[i + 1] == '"' || s[i + 1] == '\\')) i++
                    current.append(s[i])
                    i++
                }
            }
            c == '\\' -> {
                inArg = true
                if (i + 1 < s.length) i++
                current.append(s[i])
            }
            else -> {
                inArg = true
                current.append(c)
            }
        }
        i++
    }
    if (inArg) args += current.toString()
    return args
}

/**
 * Joins arguments into one line that [splitCommandLine] splits back into exactly [args]: plain arguments
 * are written as-is, anything else in single quotes (an embedded `'` becomes `'\''`).
 */
fun joinCommandLine(args: List<String>): String = args.joinToString(" ") { arg ->
    if (arg.isNotEmpty() && arg.all(::isSafe)) arg else "'" + arg.replace("'", "'\\''") + "'"
}
