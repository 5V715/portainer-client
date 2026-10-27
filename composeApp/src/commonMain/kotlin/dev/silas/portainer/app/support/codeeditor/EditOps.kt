package dev.silas.portainer.app.support.codeeditor

/**
 * Editor text plus selection. [selection] is `start until end` (end exclusive), so a caret at offset `p`
 * is `p until p` (an empty range whose [IntRange.first] is `p`). Use [selStart] / [selEnd].
 */
data class EditorText(val text: String, val selection: IntRange)

val EditorText.selStart: Int get() = selection.first
val EditorText.selEnd: Int get() = maxOf(selection.first, selection.last + 1)

const val INDENT = "  "

/** Start offsets of the lines the selection touches; a selection ending at column 0 skips that line. */
private fun selectedLineStarts(t: EditorText): List<Int> {
    val text = t.text
    val start = t.selStart.coerceIn(0, text.length)
    var end = t.selEnd.coerceIn(start, text.length)
    if (end > start && text[end - 1] == '\n') end--
    val first = text.lastIndexOf('\n', start - 1) + 1
    val starts = mutableListOf(first)
    var i = text.indexOf('\n', first)
    while (i in 0 until end) {
        starts.add(i + 1)
        i = text.indexOf('\n', i + 1)
    }
    return starts
}

/** Inserts [INDENT] at the start of each selected line; the selection stays on the same text. */
fun indent(t: EditorText): EditorText {
    val starts = selectedLineStarts(t)
    val text = buildString(t.text.length + starts.size * INDENT.length) {
        var from = 0
        starts.forEach {
            append(t.text, from, it)
            append(INDENT)
            from = it
        }
        append(t.text, from, t.text.length)
    }
    fun map(p: Int) = p + INDENT.length * starts.count { it <= p }
    return EditorText(text, map(t.selStart) until map(t.selEnd))
}

/** What the Tab key does: two spaces at a caret, or [indent] when text is selected. */
fun insertTab(t: EditorText): EditorText {
    if (t.selEnd > t.selStart) return indent(t)
    val p = t.selStart.coerceIn(0, t.text.length)
    val caret = p + INDENT.length
    return EditorText(t.text.substring(0, p) + INDENT + t.text.substring(p), caret until caret)
}

/**
 * Removes up to [INDENT].length leading spaces (or one tab) from each selected line; the selection stays on
 * the same text, and an end inside the removed indent moves to the line start.
 */
fun outdent(t: EditorText): EditorText {
    val src = t.text
    // (line start, chars removed) for each touched line.
    val removals = selectedLineStarts(t).map { start ->
        var n = 0
        if (start < src.length && src[start] == '\t') {
            n = 1
        } else {
            while (n < INDENT.length && start + n < src.length && src[start + n] == ' ') n++
        }
        start to n
    }.filter { it.second > 0 }
    if (removals.isEmpty()) return t
    val text = buildString(src.length) {
        var from = 0
        removals.forEach { (start, n) ->
            append(src, from, start)
            from = start + n
        }
        append(src, from, src.length)
    }
    fun map(p: Int): Int {
        var shift = 0
        for ((start, n) in removals) {
            when {
                p >= start + n -> shift += n
                p > start -> return start - shift
                else -> break
            }
        }
        return p - shift
    }
    return EditorText(text, map(t.selStart) until map(t.selEnd))
}

/**
 * Undo/redo snapshots, keeping at most [limit] undo steps. [push] every state the editor reaches.
 *
 * Grouping rule: consecutive single-character edits at a moving caret coalesce into one undo step.
 * Typing groups a word together with the whitespace typed after it; a typed newline, a non-blank character
 * typed after whitespace, a caret jump, a switch between typing and deleting, a multi-character change
 * (paste, indent, outdent) or an undo/redo all start a new step. Backspace or Delete runs coalesce the same
 * way, and deleting a newline starts a new step.
 */
class EditHistory(private val limit: Int = 200) {
    private val states = ArrayList<EditorText>()
    private var index = -1

    private sealed interface LastEdit
    private data class Typed(val caret: Int, val lastChar: Char) : LastEdit
    private data class Deleted(val at: Int) : LastEdit
    private var last: LastEdit? = null

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < states.size - 1

    fun push(t: EditorText) {
        if (index < 0) {
            states.add(t)
            index = 0
            return
        }
        val current = states[index]
        if (current.text == t.text) {
            if (current.selection != t.selection) last = null
            states[index] = t
            return
        }
        val edit = classify(current.text, t.text)
        val coalesce = when {
            edit is Typed && last is Typed -> {
                val prev = last as Typed
                edit.caret == prev.caret + 1 && edit.lastChar != '\n' &&
                    !(isBlank(prev.lastChar) && !isBlank(edit.lastChar))
            }
            edit is Deleted && last is Deleted -> {
                val prev = (last as Deleted).at
                edit.at == prev - 1 || edit.at == prev
            }
            else -> false
        }
        last = edit
        while (states.size > index + 1) states.removeAt(states.size - 1)
        if (coalesce) {
            states[index] = t
            return
        }
        states.add(t)
        index++
        if (states.size > limit + 1) {
            states.removeAt(0)
            index--
        }
    }

    fun undo(): EditorText? {
        if (!canUndo) return null
        index--
        last = null
        return states[index]
    }

    fun redo(): EditorText? {
        if (!canRedo) return null
        index++
        last = null
        return states[index]
    }

    private fun isBlank(c: Char) = c == ' ' || c == '\t' || c == '\n'

    /** A single typed character (caret = its offset), a single deleted non-newline character, or null. */
    private fun classify(old: String, new: String): LastEdit? {
        val delta = new.length - old.length
        if (delta != 1 && delta != -1) return null
        val max = minOf(old.length, new.length)
        var p = 0
        while (p < max && old[p] == new[p]) p++
        // The remainder after the changed character must match exactly.
        return if (delta == 1) {
            if (new.regionMatches(p + 1, old, p, old.length - p)) Typed(p, new[p]) else null
        } else {
            if (old[p] != '\n' && old.regionMatches(p + 1, new, p, new.length - p)) Deleted(p) else null
        }
    }
}

/** Non-overlapping matches of [query] in [text], left to right; an empty query matches nothing. */
fun findAll(text: String, query: String, ignoreCase: Boolean = true): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase)
        if (at < 0) return out
        out.add(at until at + query.length)
        from = at + query.length
    }
}

/** Index of the first match starting after [caret], wrapping to the first; null when there are none. */
fun nextMatchIndex(matches: List<IntRange>, caret: Int): Int? {
    if (matches.isEmpty()) return null
    return matches.indexOfFirst { it.first > caret }.takeIf { it >= 0 } ?: 0
}

/** Index of the last match starting before [caret], wrapping to the last; null when there are none. */
fun previousMatchIndex(matches: List<IntRange>, caret: Int): Int? {
    if (matches.isEmpty()) return null
    return matches.indexOfLast { it.first < caret }.takeIf { it >= 0 } ?: matches.lastIndex
}

/** 1-based line number of [offset] in [text] (lines split on `\n`). */
fun lineOf(text: String, offset: Int): Int {
    var line = 1
    val end = offset.coerceIn(0, text.length)
    for (i in 0 until end) if (text[i] == '\n') line++
    return line
}

/** Number of lines in [text]; the empty text has one line. */
fun lineCount(text: String): Int = text.count { it == '\n' } + 1
