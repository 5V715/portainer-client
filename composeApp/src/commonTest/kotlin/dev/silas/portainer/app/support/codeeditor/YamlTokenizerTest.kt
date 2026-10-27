package dev.silas.portainer.app.support.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class YamlTokenizerTest {

    /** The tokens of [text] as (covered text, kind) pairs, which reads better in failures than offsets. */
    private fun spans(text: String): List<Pair<String, TokenKind>> =
        tokenizeYaml(text).map { text.substring(it.start, it.end) to it.kind }

    private fun kindOf(text: String, piece: String): TokenKind? {
        val start = text.indexOf(piece)
        return tokenizeYaml(text).firstOrNull { it.start == start && it.end == start + piece.length }?.kind
    }

    @Test
    fun keysPunctuationAndScalars() {
        assertEquals(
            listOf("services" to TokenKind.Key, ":" to TokenKind.Punct),
            spans("services:"),
        )
        assertEquals(
            listOf(
                "image" to TokenKind.Key, ":" to TokenKind.Punct, "nginx:1.27" to TokenKind.Scalar,
            ),
            spans("    image: nginx:1.27"),
        )
        assertEquals(
            listOf("-" to TokenKind.Punct, "8080:80" to TokenKind.Scalar),
            spans("  - 8080:80"),
        )
    }

    @Test
    fun quotedStringsAndQuotedKeys() {
        assertEquals(
            listOf("command" to TokenKind.Key, ":" to TokenKind.Punct, "\"echo hi\"" to TokenKind.StringLit),
            spans("command: \"echo hi\""),
        )
        assertEquals(TokenKind.StringLit, kindOf("a: 'it''s'", "'it''s'"))
        assertEquals(TokenKind.StringLit, kindOf("a: \"say \\\"x\\\"\"", "\"say \\\"x\\\"\""))
        assertEquals(TokenKind.Key, kindOf("\"quoted key\": 1", "\"quoted key\""))
    }

    @Test
    fun comments() {
        assertEquals(listOf("# just a note" to TokenKind.Comment), spans("  # just a note"))
        assertEquals(TokenKind.Comment, kindOf("image: nginx # pinned later", "# pinned later"))
        // A `#` glued to a plain scalar is part of it, not a comment.
        assertEquals(TokenKind.Scalar, kindOf("color: a#b", "a#b"))
    }

    @Test
    fun hashInsideAQuotedStringIsNotAComment() {
        val text = "password: \"abc # not a comment\" # real"
        assertEquals(TokenKind.StringLit, kindOf(text, "\"abc # not a comment\""))
        assertEquals(TokenKind.Comment, kindOf(text, "# real"))
        assertTrue(tokenizeYaml(text).none { it.kind == TokenKind.Comment && it.start < text.indexOf("# real") })
        assertEquals(TokenKind.StringLit, kindOf("x: 'a # b'", "'a # b'"))
    }

    @Test
    fun anchorsAliasesAndMergeKeys() {
        val text = "x-common: &defaults\n  restart: always\nweb:\n  <<: *defaults\n  env: *defaults"
        assertEquals(TokenKind.Anchor, kindOf(text, "&defaults"))
        assertEquals(TokenKind.Key, kindOf(text, "<<"))
        val aliases = tokenizeYaml(text).filter { it.kind == TokenKind.Alias }.map { text.substring(it.start, it.end) }
        assertEquals(listOf("*defaults", "*defaults"), aliases)
    }

    @Test
    fun interpolationInsideStringsAndScalars() {
        val text = "image: \"repo/app:\${TAG:-latest}\""
        assertEquals(
            listOf(
                "image" to TokenKind.Key, ":" to TokenKind.Punct,
                "\"repo/app:" to TokenKind.StringLit, "\${TAG:-latest}" to TokenKind.Interpolation,
                "\"" to TokenKind.StringLit,
            ),
            spans(text),
        )
        assertEquals(TokenKind.Interpolation, kindOf("port: \${PORT}", "\${PORT}"))
        assertEquals(TokenKind.Interpolation, kindOf("p: a\${X}b", "\${X}"))
        // `$$` is Compose's escape for a literal dollar sign.
        assertTrue(tokenizeYaml("a: \$\${NOT}").none { it.kind == TokenKind.Interpolation })
        // Single-quoted YAML strings are still interpolated by Compose.
        assertEquals(TokenKind.Interpolation, kindOf("a: 'x\${Y}'", "\${Y}"))
    }

    @Test
    fun flowCollections() {
        assertEquals(
            listOf(
                "ports" to TokenKind.Key, ":" to TokenKind.Punct, "[" to TokenKind.Punct,
                "80" to TokenKind.Scalar, "," to TokenKind.Punct, "443" to TokenKind.Scalar, "]" to TokenKind.Punct,
            ),
            spans("ports: [80, 443]"),
        )
        assertEquals(TokenKind.Key, kindOf("l: {a: 1}", "a"))
    }

    @Test
    fun blockScalarLinesAreStrings() {
        val text = "command: |\n  echo \${HOME}\n  # not a comment\nnext: 1"
        assertEquals(TokenKind.Punct, kindOf(text, "|"))
        assertEquals(TokenKind.StringLit, kindOf(text, "echo "))
        assertEquals(TokenKind.Interpolation, kindOf(text, "\${HOME}"))
        assertEquals(TokenKind.StringLit, kindOf(text, "# not a comment"))
        assertEquals(TokenKind.Key, kindOf(text, "next"))
    }

    @Test
    fun blockScalarInsideASequenceItemEndsAtTheNextSiblingKey() {
        val text = "services:\n  - command: |\n      echo hi\n\n      echo two\n    image: nginx\n  - name: b"
        assertEquals(TokenKind.StringLit, kindOf(text, "echo hi"))
        assertEquals(TokenKind.StringLit, kindOf(text, "echo two"))
        assertEquals(TokenKind.Key, kindOf(text, "image"))
        assertEquals(TokenKind.Scalar, kindOf(text, "nginx"))
        assertEquals(TokenKind.Key, kindOf(text, "name"))
        // An empty block: the sibling right after it is still a key.
        assertEquals(TokenKind.Key, kindOf("  - command: |\n    image: x", "image"))
    }

    @Test
    fun blockContentIndentComesFromItsFirstLine() {
        val text = "a: >\n    deep\n  shallow: 1"
        assertEquals(TokenKind.StringLit, kindOf(text, "deep"))
        assertEquals(TokenKind.Key, kindOf(text, "shallow"))
    }

    @Test
    fun interpolationInsideAFlowCollectionStaysOneToken() {
        val text = "environment: [FOO=\${BAR}, X=1]"
        assertEquals(TokenKind.Scalar, kindOf(text, "FOO="))
        assertEquals(TokenKind.Interpolation, kindOf(text, "\${BAR}"))
        assertEquals(TokenKind.Scalar, kindOf(text, "X=1"))
        assertEquals(TokenKind.Punct, kindOf(text, "]"))
    }

    @Test
    fun moreScalarAndKeyShapes() {
        assertEquals(TokenKind.Scalar, kindOf("url: http://example.com/#frag", "http://example.com/#frag"))
        assertEquals(
            listOf("-" to TokenKind.Punct, "key" to TokenKind.Key, ":" to TokenKind.Punct, "v" to TokenKind.Scalar),
            spans("  - key: v"),
        )
        assertEquals(TokenKind.Key, kindOf("\"a:b\": 1", "\"a:b\""))
        assertEquals(TokenKind.Key, kindOf("'8080:80': x", "'8080:80'"))
    }

    @Test
    fun manyUnclosedInterpolationsStayFast() {
        val text = (1..3_000).joinToString("\n") { "  key$it: \"\${UNCLOSED $it" }
        val best = (1..3).minOf {
            val mark = TimeSource.Monotonic.markNow()
            tokenizeYaml(text)
            mark.elapsedNow().inWholeMilliseconds
        }
        assertTrue(best < 200, "tokenizing took $best ms")
        assertTrue(tokenizeYaml(text).none { it.kind == TokenKind.Interpolation })
    }

    @Test
    fun documentMarkersAndTags() {
        assertEquals(listOf("---" to TokenKind.Punct), spans("---"))
        assertEquals(TokenKind.Plain, kindOf("a: !!str 1", "!!str"))
    }

    @Test
    fun tokensAreSortedNonOverlappingAndInBounds() {
        val text = sampleCompose(5)
        var last = 0
        tokenizeYaml(text).forEach {
            assertTrue(it.start >= last && it.end > it.start && it.end <= text.length, "bad token $it")
            last = it.end
        }
    }

    @Test
    fun tokenizesThreeThousandLinesUnderTwoHundredMillis() {
        val text = sampleCompose(3_000 / 10)
        assertEquals(3_000, text.lines().size)
        val best = (1..3).minOf {
            val mark = TimeSource.Monotonic.markNow()
            tokenizeYaml(text)
            mark.elapsedNow().inWholeMilliseconds
        }
        assertTrue(best < 200, "tokenizing 3,000 lines took $best ms")
    }

    /** [services] services of 10 lines each (the last line has no trailing newline). */
    private fun sampleCompose(services: Int): String = buildString {
        repeat(services) { i ->
            append("svc$i: &svc$i\n")
            append("  image: \"registry.example.com/app:\${TAG:-latest}\" # pinned\n")
            append("  <<: *base\n")
            append("  ports: [\"80$i:80\", 443]\n")
            append("  environment:\n")
            append("    - KEY=value\n")
            append("    - 'QUOTED=a # b'\n")
            append("  command: |\n")
            append("    echo \$\$HOME \${USER}\n")
            append("  restart: unless-stopped")
            if (i < services - 1) append('\n')
        }
    }
}
