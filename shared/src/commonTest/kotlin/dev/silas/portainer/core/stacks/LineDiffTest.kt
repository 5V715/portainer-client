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

    private fun lcs(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size)
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        return dp[a.size][b.size]
    }

    @Test
    fun sameCountEqualsLcsOnRandomInputs() {
        val rnd = kotlin.random.Random(11)
        repeat(80) {
            val o = List(rnd.nextInt(0, 30)) { "l${rnd.nextInt(0, 6)}" }
            val n = List(rnd.nextInt(0, 30)) { "l${rnd.nextInt(0, 6)}" }
            val d = flat(lineDiff(o.joinToString("") { "$it\n" }, n.joinToString("") { "$it\n" }, Int.MAX_VALUE))
            assertEquals(lcs(o, n), d.count { it is DiffLine.Same })
            assertEquals(o.size, d.count { it !is DiffLine.Added })
            assertEquals(n.size, d.count { it !is DiffLine.Removed })
        }
    }

    @Test
    fun movedBlockAndSingleLine() {
        val blocks = lines(20)
        val moved = (11..20).joinToString("") { "line $it\n" } + (1..10).joinToString("") { "line $it\n" }
        val d = flat(lineDiff(blocks, moved, Int.MAX_VALUE))
        assertEquals(10, d.count { it is DiffLine.Same })
        replay(blocks, moved)
        assertEquals(listOf(DiffLine.Removed("a", 1), DiffLine.Added("b", 1)), flat(lineDiff("a\n", "b\n")))
        assertEquals(listOf(DiffLine.Removed("a", 1), DiffLine.Removed(NO_EOL_MARKER, 1), DiffLine.Added("b", 1), DiffLine.Added(NO_EOL_MARKER, 1)), flat(lineDiff("a", "b")))
    }

    @Test
    fun partiallySimilarPastTheCapIsStillFaithful() {
        fun build(tag: String) = (1..1000).joinToString("") { "$tag-a $it\n" } + (1..500).joinToString("") { "shared $it\n" } + (1..1000).joinToString("") { "$tag-b $it\n" }
        val old = build("old")
        val new = build("new")
        val best = (1..3).minOf { measureTime { lineDiff(old, new) } }
        assertTrue(best.inWholeMilliseconds < 1000, "best of 3 took $best")
        val d = flat(lineDiff(old, new, Int.MAX_VALUE))
        assertEquals(2500, d.count { it !is DiffLine.Added })
        assertEquals(2500, d.count { it !is DiffLine.Removed })
        replay(old, new)
    }

    @Test
    fun fallbackPerfBestOfThree() {
        val old = lines(3000) { "old $it" }
        val new = lines(3000) { "new $it" }
        val best = (1..3).minOf { measureTime { lineDiff(old, new) } }
        assertTrue(best.inWholeMilliseconds < 1000, "best of 3 took $best")
    }

    @Test
    fun lastHunkReportsCollapsedAfter() {
        val old = lines(20)
        val h = lineDiff(old, old.replace("line 5\n", "x\n"))
        assertEquals(0, h.dropLast(1).sumOf { it.collapsedAfter })
        assertEquals(20 - 8, h.single().collapsedAfter) // lines 9..20
        val two = lineDiff(old, old.replace("line 2\n", "x\n").replace("line 18\n", "y\n"))
        assertEquals(2, two.size)
        assertEquals(0, two[0].collapsedAfter)
        assertEquals(0, two[1].collapsedAfter) // line 18 + 3 reaches the end
        val mid = lineDiff(old, old.replace("line 12\n", "y\n"))
        assertEquals(5, mid.single().collapsedAfter) // lines 16..20
        assertEquals(0, lineDiff("a\nb\n", "a\nc\n").single().collapsedAfter)
    }

    @Test
    fun hugeContextIsClamped() {
        val old = lines(10)
        val h = lineDiff(old, old.replace("line 5\n", "x\n"), Int.MAX_VALUE)
        assertEquals(1, h.size)
        assertEquals(0, h[0].collapsedBefore)
        assertEquals(11, h[0].lines.size)
        assertTrue(lineDiff(old, old, Int.MAX_VALUE).isEmpty())
        assertEquals(2, lineDiff(old, old.replace("line 5\n", "x\n"), -5).single().lines.size)
    }

    @Test
    fun envValuesAreEscapedQuotedAndMasked() {
        val old = listOf(EnvVar("A", ""), EnvVar("B", "x"), EnvVar("C", "u"), EnvVar("D", "p"))
        val new = listOf(EnvVar("A", "a b "), EnvVar("B", "l1\nl2\t\r"), EnvVar("C", "postgres://db:5432/x"), EnvVar("E", "http://h/p?q=1"))
        assertEquals(
            listOf("Changed A: \"\" -> \"a b \"", "Changed B: x -> \"l1\\nl2\\t\\r\"", "Changed C: u -> postgres://db:5432/x", "Removed D=p", "Added E=http://h/p?q=1"),
            envDiff(old, new),
        )
        assertEquals(listOf("Added F=\"\""), envDiff(emptyList(), listOf(EnvVar("F", ""))))
    }

    @Test
    fun envBroaderSecretNamesHideValues() {
        for (n in listOf("DB_PWD", "AUTH_HEADER", "SENTRY_DSN", "JWT_X", "PW_SALT", "SESSION_COOKIE", "PRIVATE_URL", "AWS_CREDENTIALS")) {
            val out = envDiff(listOf(EnvVar(n, "old-v")), listOf(EnvVar(n, "new-v")))
            assertEquals(listOf("Changed $n"), out)
            assertEquals(listOf("Added $n"), envDiff(emptyList(), listOf(EnvVar(n, "zzz"))))
            assertEquals(listOf("Removed $n"), envDiff(listOf(EnvVar(n, "zzz")), emptyList()))
        }
        assertEquals(listOf("Added PORT=80"), envDiff(emptyList(), listOf(EnvVar("PORT", "80"))))
    }

    @Test
    fun envValuesThatMayHoldCredentialsAreHidden() {
        val risky = listOf(
            "postgres://bob:p@ss@host", "u:pa/ss@h", "bob:pw@host:5432", "jdbc:postgresql://u:pw@h/db",
            "mongodb://u:pw@h1:27017,h2:27017/x", "https://h/x?api_key=abc", "https://h/x?a=1&token=", "https://h/x?Secret=1#f",
        )
        for (v in risky) {
            assertEquals(listOf("Added URL"), envDiff(emptyList(), listOf(EnvVar("URL", v))), v)
            assertEquals(listOf("Removed URL"), envDiff(listOf(EnvVar("URL", v)), emptyList()), v)
            assertEquals(listOf("Changed URL"), envDiff(listOf(EnvVar("URL", "plain")), listOf(EnvVar("URL", v))), v)
            assertEquals(listOf("Changed URL"), envDiff(listOf(EnvVar("URL", v)), listOf(EnvVar("URL", "plain"))), v)
        }
        assertEquals(listOf("Added PORT=8080"), envDiff(emptyList(), listOf(EnvVar("PORT", "8080"))))
        assertEquals(listOf("Added URL=https://h/x?page=2&sort=asc"), envDiff(emptyList(), listOf(EnvVar("URL", "https://h/x?page=2&sort=asc"))))
        assertEquals(listOf("Added db_password"), envDiff(emptyList(), listOf(EnvVar("db_password", "x"))))
        assertEquals(listOf("Changed api_token"), envDiff(listOf(EnvVar("api_token", "a")), listOf(EnvVar("api_token", "b"))))
    }

    @Test
    fun fragmentAndMatrixParametersThatMayHoldCredentialsAreHiddenToo() {
        for (v in listOf("https://h/cb#access_token=abc123", "https://h/x;password=hunter2", "https://h/x;a=1;Token=t")) {
            assertEquals(listOf("Added URL"), envDiff(emptyList(), listOf(EnvVar("URL", v))), v)
            assertEquals(listOf("Changed URL"), envDiff(listOf(EnvVar("URL", "plain")), listOf(EnvVar("URL", v))), v)
        }
        // Harmless fragment and matrix parameters still show.
        assertEquals(listOf("Added URL=https://h/x;v=2#page=3"), envDiff(emptyList(), listOf(EnvVar("URL", "https://h/x;v=2#page=3"))))
    }
}
