package dev.silas.portainer.core.stacks

import dev.silas.portainer.core.model.EnvVar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class LineDiffTest {
    private fun lines(n: Int, f: (Int) -> String = { "line $it" }) = (1..n).joinToString("\n") { f(it) } + "\n"
    private fun flat(h: List<DiffHunk>) = h.flatMap { it.lines }

    /** Rebuilds old/new line lists from a full-context diff to prove the script is faithful. */
    private fun replay(old: String, new: String) {
        val all = flat(lineDiff(old, new, context = Int.MAX_VALUE / 4))
        val o = all.mapNotNull { when (it) { is DiffLine.Same -> it.text; is DiffLine.Removed -> it.text; else -> null } }
        val n = all.mapNotNull { when (it) { is DiffLine.Same -> it.text; is DiffLine.Added -> it.text; else -> null } }
        val norm = { s: String -> s.replace("\r\n", "\n").split("\n").let { if (it.last().isEmpty()) it.dropLast(1) else it } }
        val ol = norm(old).filter { it != NO_EOL_MARKER }
        val nl = norm(new).filter { it != NO_EOL_MARKER }
        assertEquals(ol, o.filter { it != NO_EOL_MARKER })
        assertEquals(nl, n.filter { it != NO_EOL_MARKER })
    }

    @Test
    fun identicalHasNoHunks() {
        assertTrue(lineDiff(lines(10), lines(10)).isEmpty())
        assertTrue(lineDiff("", "").isEmpty())
    }

    @Test
    fun oneChangedLineHasThreeLinesContext() {
        val old = lines(20)
        val new = old.replace("line 10\n", "LINE ten\n")
        val h = lineDiff(old, new)
        assertEquals(1, h.size)
        assertEquals(6, h[0].collapsedBefore)
        assertEquals(
            listOf(
                DiffLine.Same("line 7", 7, 7), DiffLine.Same("line 8", 8, 8), DiffLine.Same("line 9", 9, 9),
                DiffLine.Removed("line 10", 10), DiffLine.Added("LINE ten", 10),
                DiffLine.Same("line 11", 11, 11), DiffLine.Same("line 12", 12, 12), DiffLine.Same("line 13", 13, 13),
            ),
            h[0].lines,
        )
    }

    @Test
    fun farApartChangesGiveTwoHunksWithCollapsedCounts() {
        val old = lines(40)
        val new = old.replace("line 5\n", "five\n").replace("line 30\n", "thirty\n")
        val h = lineDiff(old, new)
        assertEquals(2, h.size)
        assertEquals(1, h[0].collapsedBefore) // line 1 hidden
        // first hunk ends at line 8, second starts at 27 -> lines 9..26 hidden = 18
        assertEquals(18, h[1].collapsedBefore)
    }

    @Test
    fun nearbyChangesMergeIntoOneHunk() {
        val old = lines(30)
        val new = old.replace("line 10\n", "a\n").replace("line 17\n", "b\n") // 6 same lines between = 2*context
        assertEquals(1, lineDiff(old, new).size)
        val new2 = old.replace("line 10\n", "a\n").replace("line 18\n", "b\n") // 7 between
        assertEquals(2, lineDiff(old, new2).size)
    }

    @Test
    fun insertDeleteAndReplaceRuns() {
        val old = "a\nb\nc\nd\ne\n"
        val new = "a\nX\nY\nZ\nd\nnew1\nnew2\n" // replace b,c by X,Y,Z; drop e; add two
        val all = flat(lineDiff(old, new))
        assertEquals(
            listOf(
                DiffLine.Same("a", 1, 1), DiffLine.Removed("b", 2), DiffLine.Removed("c", 3),
                DiffLine.Added("X", 2), DiffLine.Added("Y", 3), DiffLine.Added("Z", 4),
                DiffLine.Same("d", 4, 5), DiffLine.Removed("e", 5), DiffLine.Added("new1", 6), DiffLine.Added("new2", 7),
            ),
            all,
        )
        replay(old, new)
    }

    @Test
    fun pureInsertAndPureDelete() {
        assertEquals(listOf(DiffLine.Added("a", 1), DiffLine.Added("b", 2)), flat(lineDiff("", "a\nb\n")))
        assertEquals(listOf(DiffLine.Removed("a", 1)), flat(lineDiff("a\n", "")))
    }

    @Test
    fun crlfAndLfAreEqual() {
        assertTrue(lineDiff("a\r\nb\r\n", "a\nb\n").isEmpty())
        val h = flat(lineDiff("a\r\nb\r\nc\r\n", "a\nB\nc\n"))
        assertEquals(listOf("b"), h.filterIsInstance<DiffLine.Removed>().map { it.text })
        assertEquals(listOf("B"), h.filterIsInstance<DiffLine.Added>().map { it.text })
    }

    @Test
    fun trailingNewlineOnlyDifferenceIsVisible() {
        val h = flat(lineDiff("a\nb", "a\nb\n"))
        assertEquals(
            listOf(
                DiffLine.Same("a", 1, 1),
                DiffLine.Removed("b", 2), DiffLine.Removed(NO_EOL_MARKER, 2),
                DiffLine.Added("b", 2),
            ),
            h,
        )
        val rev = flat(lineDiff("a\nb\n", "a\nb"))
        assertEquals(
            listOf(DiffLine.Same("a", 1, 1), DiffLine.Removed("b", 2), DiffLine.Added("b", 2), DiffLine.Added(NO_EOL_MARKER, 2)),
            rev,
        )
        // both lacking the newline: equal
        assertTrue(lineDiff("a\nb", "a\nb").isEmpty())
        // a blank line added at the end is a real line, not hidden
        assertEquals(1, flat(lineDiff("a\n", "a\n\n")).count { it is DiffLine.Added })
    }

    @Test
    fun replayIsFaithfulOnRandomEdits() {
        val rnd = kotlin.random.Random(7)
        repeat(60) {
            val old = (1..rnd.nextInt(0, 40)).joinToString("\n") { "l${rnd.nextInt(0, 8)}" } + if (rnd.nextBoolean()) "\n" else ""
            val new = (1..rnd.nextInt(0, 40)).joinToString("\n") { "l${rnd.nextInt(0, 8)}" } + if (rnd.nextBoolean()) "\n" else ""
            replay(old, new)
        }
    }

    @Test
    fun totallyDifferentLargeFilesFallBackToWholeFileHunk() {
        val old = lines(3000) { "old $it" }
        val new = lines(3000) { "new $it" }
        val t = measureTime {
            val h = lineDiff(old, new)
            assertEquals(1, h.size)
            assertEquals(3000, h[0].lines.count { it is DiffLine.Removed })
            assertEquals(3000, h[0].lines.count { it is DiffLine.Added })
            assertEquals(0, h[0].lines.count { it is DiffLine.Same })
        }
        assertTrue(t.inWholeMilliseconds < 1000, "took $t")
    }

    @Test
    fun largeFileWithFewChangesIsFast() {
        val old = lines(3000)
        val new = old.replace("line 100\n", "x\n").replace("line 1500\n", "").replace("line 2900\n", "line 2900\ninserted\n")
        val best = (1..3).minOf { measureTime { lineDiff(old, new) } }
        assertTrue(best.inWholeMilliseconds < 300, "best of 3 took $best")
        assertEquals(3, lineDiff(old, new).size)
        replay(old, new)
    }

    @Test
    fun largeFileWithManyScatteredChangesStaysCorrectAndFast() {
        val old = lines(3000)
        val new = (1..3000).joinToString("\n") { if (it % 7 == 0) "changed $it" else "line $it" } + "\n"
        val best = (1..3).minOf { measureTime { lineDiff(old, new) } }
        assertTrue(best.inWholeMilliseconds < 300, "best of 3 took $best")
        replay(old, new)
    }

    // envDiff

    @Test
    fun envDiffOrdersByNameAndShowsPlainValues() {
        val old = listOf(EnvVar("B", "1"), EnvVar("A", "x"), EnvVar("D", "gone"))
        val new = listOf(EnvVar("C", "new"), EnvVar("B", "2"), EnvVar("A", "x"))
        assertEquals(listOf("Changed B: 1 -> 2", "Added C=new", "Removed D=gone"), envDiff(old, new))
    }

    @Test
    fun envDiffHidesSecretLookingValues() {
        val old = listOf(EnvVar("DB_PASSWORD", "hunter2"), EnvVar("OLD_Token", "t0"), EnvVar("API_KEY", "k1"))
        val new = listOf(EnvVar("DB_PASSWORD", "hunter3"), EnvVar("NEW_SECRET", "s3cr3t"), EnvVar("API_KEY", "k1"))
        val out = envDiff(old, new)
        assertEquals(listOf("Added NEW_SECRET", "Changed DB_PASSWORD", "Removed OLD_Token").sorted(), out.sorted())
        assertTrue(out.none { l -> listOf("hunter", "t0", "s3cr3t").any { it in l } })
    }

    @Test
    fun envDiffDuplicateNamesAndNoChange() {
        assertTrue(envDiff(listOf(EnvVar("A", "1"), EnvVar("A", "2")), listOf(EnvVar("A", "1"), EnvVar("A", "2"))).isEmpty())
        val out = envDiff(listOf(EnvVar("A", "1")), listOf(EnvVar("A", "1"), EnvVar("A", "2")))
        assertEquals(listOf("Added A=2"), out)
        assertEquals(listOf("Removed A=2"), envDiff(listOf(EnvVar("A", "1"), EnvVar("A", "2")), listOf(EnvVar("A", "1"))))
        assertEquals(listOf("Changed A: 2 -> 3"), envDiff(listOf(EnvVar("A", "1"), EnvVar("A", "2")), listOf(EnvVar("A", "1"), EnvVar("A", "3"))))
    }
}
