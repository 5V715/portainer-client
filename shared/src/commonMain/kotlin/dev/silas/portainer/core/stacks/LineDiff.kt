package dev.silas.portainer.core.stacks

import dev.silas.portainer.core.model.EnvVar

/** Shown (as a line) next to a changed last line that has no trailing newline, like `git diff`. */
const val NO_EOL_MARKER = "\\ No newline at end of file"

/** Line numbers are 1-based. [Added]/[Removed] markers for a missing final newline reuse the number of the line they annotate. */
sealed interface DiffLine {
    data class Same(val text: String, val oldNo: Int, val newNo: Int) : DiffLine
    data class Added(val text: String, val newNo: Int) : DiffLine
    data class Removed(val text: String, val oldNo: Int) : DiffLine
}

/**
 * A run of changed lines with context. [collapsedBefore] is the number of unchanged lines hidden between the
 * previous hunk (or the start of the file) and this hunk. [collapsedAfter] is non-zero only on the last hunk: the
 * unchanged lines hidden after it up to the end of the file.
 */
data class DiffHunk(val lines: List<DiffLine>, val collapsedBefore: Int, val collapsedAfter: Int = 0)

/** Largest edit distance Myers is allowed to explore (memory is about D^2 ints, time about (N+M)*D). */
private const val MAX_EDIT_DISTANCE = 1500

private class Doc(val text: List<String>, val key: List<String>, val noEol: Boolean)

private fun parse(s: String): Doc {
    if (s.isEmpty()) return Doc(emptyList(), emptyList(), false)
    var parts = s.replace("\r\n", "\n").split('\n')
    val endsWithNewline = parts.last().isEmpty()
    if (endsWithNewline) parts = parts.dropLast(1)
    val noEol = !endsWithNewline
    // The unterminated last line compares unequal to the same text with a newline, so the difference is visible.
    val key = if (noEol) parts.dropLast(1) + (parts.last() + "\u0000") else parts
    return Doc(parts, key, noEol)
}

/**
 * Unified-style line diff of [old] to [new] with [context] unchanged lines around each change.
 *
 * CRLF and LF compare equal, so a file converted only from CRLF to LF yields no diff. A lone `\r` is not treated as a
 * line break. UIs must key rows by list index, not line number: a [NO_EOL_MARKER] line shares the number of the line
 * it annotates.
 *
 * A difference only in the final newline is reported as the last line removed and added,
 * with a [NO_EOL_MARKER] line after the side that lacks the newline.
 *
 * Cost: common prefix and suffix are trimmed first, then Myers O(ND) runs on the rest, so large files with few
 * changes are fast. Edit distance is capped at 1500; past that (essentially unrelated files) the whole differing
 * middle is reported as one block of Removed lines followed by Added lines, so work and memory stay bounded
 * (roughly 1500 x 3000 steps and a few million ints at worst).
 */
fun lineDiff(old: String, new: String, context: Int = 3): List<DiffHunk> {
    val a = parse(old)
    val b = parse(new)
    val ids = HashMap<String, Int>()
    val x = IntArray(a.key.size) { ids.getOrPut(a.key[it]) { ids.size } }
    val y = IntArray(b.key.size) { ids.getOrPut(b.key[it]) { ids.size } }

    var prefix = 0
    while (prefix < x.size && prefix < y.size && x[prefix] == y[prefix]) prefix++
    var suffix = 0
    while (suffix < x.size - prefix && suffix < y.size - prefix && x[x.size - 1 - suffix] == y[y.size - 1 - suffix]) suffix++
    if (prefix == x.size && prefix == y.size) return emptyList()

    val aEnd = x.size - suffix
    val bEnd = y.size - suffix
    // ops over the middle: true = keep pair, false/null handled via three lists
    val script = myers(x, prefix, aEnd, y, prefix, bEnd)

    val out = ArrayList<DiffLine>(x.size + y.size + 2)
    fun same(i: Int, j: Int) = out.add(DiffLine.Same(a.text[i], i + 1, j + 1))
    fun removed(i: Int) {
        out.add(DiffLine.Removed(a.text[i], i + 1))
        if (a.noEol && i == a.text.lastIndex) out.add(DiffLine.Removed(NO_EOL_MARKER, i + 1))
    }
    fun added(j: Int) {
        out.add(DiffLine.Added(b.text[j], j + 1))
        if (b.noEol && j == b.text.lastIndex) out.add(DiffLine.Added(NO_EOL_MARKER, j + 1))
    }
    for (k in 0 until prefix) same(k, k)
    var i = prefix
    var j = prefix
    if (script == null) {
        while (i < aEnd) removed(i++)
        while (j < bEnd) added(j++)
    } else {
        // script: sequence of moves, 0 = keep, 1 = delete from old, 2 = insert from new. Emit removals before additions per run.
        var p = 0
        while (p < script.size) {
            if (script[p] == 0) { same(i++, j++); p++; continue }
            var del = 0
            var ins = 0
            while (p < script.size && script[p] != 0) { if (script[p] == 1) del++ else ins++; p++ }
            repeat(del) { removed(i++) }
            repeat(ins) { added(j++) }
        }
    }
    for (k in 0 until suffix) same(aEnd + k, bEnd + k)
    return group(out, context.coerceIn(0, out.size))
}

private fun lastKept(keep: BooleanArray): Int = keep.indexOfLast { it }

private fun group(all: List<DiffLine>, context: Int): List<DiffHunk> {
    val keep = BooleanArray(all.size)
    for ((idx, l) in all.withIndex()) {
        if (l !is DiffLine.Same) {
            for (k in maxOf(0, idx - context)..minOf(all.lastIndex, idx + context)) keep[k] = true
        }
    }
    val hunks = ArrayList<DiffHunk>()
    var idx = 0
    var hidden = 0
    while (idx < all.size) {
        if (!keep[idx]) { hidden++; idx++; continue }
        val start = idx
        while (idx < all.size && keep[idx]) idx++
        hunks.add(DiffHunk(all.subList(start, idx).toList(), hidden))
        hidden = 0
    }
    if (hunks.isNotEmpty()) {
        val trailing = all.size - (lastKept(keep) + 1)
        hunks[hunks.lastIndex] = hunks.last().copy(collapsedAfter = trailing)
    }
    return hunks
}

/** Myers shortest edit script on x[xs,xe) vs y[ys,ye). Returns moves (0 keep, 1 delete, 2 insert) or null when over budget. */
private fun myers(x: IntArray, xs: Int, xe: Int, y: IntArray, ys: Int, ye: Int): IntArray? {
    val n = xe - xs
    val m = ye - ys
    if (n == 0) return IntArray(m) { 2 }
    if (m == 0) return IntArray(n) { 1 }
    val max = minOf(n + m, MAX_EDIT_DISTANCE)
    val off = max + 1
    var v = IntArray(2 * max + 3)
    val trace = ArrayList<IntArray>()
    for (d in 0..max) {
        trace.add(v.copyOfRange(off - d - 1, off + d + 2)) // state before round d, slice for k in -d-1..d+1
        val nv = v.copyOf()
        var k = -d
        while (k <= d) {
            var px = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
            var py = px - k
            while (px < n && py < m && x[xs + px] == y[ys + py]) { px++; py++ }
            nv[off + k] = px
            if (px >= n && py >= m) return backtrack(trace, nv, d, n, m, off)
            k += 2
        }
        v = nv
    }
    return null
}

private fun backtrack(trace: List<IntArray>, last: IntArray, dEnd: Int, n: Int, m: Int, off: Int): IntArray {
    val rev = ArrayList<Int>()
    var px = n
    var py = m
    for (d in dEnd downTo 1) {
        val prev = trace[d] // slice of v before round d; index for k is (k + d + 1)
        fun pv(k: Int) = prev[k + d + 1]
        val k = px - py
        val down = k == -d || (k != d && pv(k - 1) < pv(k + 1))
        val pk = if (down) k + 1 else k - 1
        val sx = pv(pk)
        val sy = sx - pk
        val mx = if (down) sx else sx + 1
        val my = mx - k
        while (px > mx && py > my) { rev.add(0); px--; py-- }
        rev.add(if (down) 2 else 1)
        px = sx
        py = sy
    }
    while (px > 0 && py > 0) { rev.add(0); px--; py-- }
    return IntArray(rev.size) { rev[rev.size - 1 - it] }
}

private val SECRET_NAME = Regex("pass|pwd|secret|token|key|auth|cred|private|dsn|jwt|salt|cookie", RegexOption.IGNORE_CASE)
private val QUERY_PARAM = Regex("[?&]([^=&#\\s]*)=")

private fun mayHoldCredentials(value: String): Boolean =
    '@' in value || QUERY_PARAM.findAll(value).any { SECRET_NAME.containsMatchIn(it.groupValues[1]) }

/**
 * Human-readable env changes ordered by name: `Added X=v`, `Changed X: old -> new`, `Removed X=v`.
 * Values are hidden entirely (also when only one side is risky) when the name matches, the value contains `@`, or it has a
 * query parameter whose name matches. For names matching `pass|pwd|secret|token|key|auth|cred|private|dsn|jwt|salt|cookie` (case-insensitive) no value is ever included (`Added X`, `Changed X`, `Removed X`).
 * Repeated names are paired in order of appearance; extra occurrences count as added or removed.
 */
fun envDiff(old: List<EnvVar>, new: List<EnvVar>): List<String> {
    val o = old.groupBy { it.name }
    val n = new.groupBy { it.name }
    val out = ArrayList<String>()
    for (name in (o.keys + n.keys).sorted()) {
        val ov = o[name].orEmpty()
        val nv = n[name].orEmpty()
        val hideName = SECRET_NAME.containsMatchIn(name)
        for (i in 0 until maxOf(ov.size, nv.size)) {
            val before = ov.getOrNull(i)
            val after = nv.getOrNull(i)
            when {
                before == null && after != null -> out.add(if (hideName || mayHoldCredentials(after.value)) "Added $name" else "Added $name=${show(after.value)}")
                after == null && before != null -> out.add(if (hideName || mayHoldCredentials(before.value)) "Removed $name" else "Removed $name=${show(before.value)}")
                before != null && after != null && before.value != after.value ->
                    out.add(if (hideName || mayHoldCredentials(before.value) || mayHoldCredentials(after.value)) "Changed $name" else "Changed $name: ${show(before.value)} -> ${show(after.value)}")
            }
        }
    }
    return out
}

/** Single-line display form: escapes \n \r \t, quotes empty or edge-whitespace values. */
private fun show(value: String): String {
    val v = value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return if (value.isEmpty() || value.first().isWhitespace() || value.last().isWhitespace()) "\"$v\"" else v
}
