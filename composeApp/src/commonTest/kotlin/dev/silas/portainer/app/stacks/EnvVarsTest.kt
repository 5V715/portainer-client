package dev.silas.portainer.app.stacks

import dev.silas.portainer.core.model.EnvVar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvVarsTest {
    private val tricky = listOf(
        EnvVar("PLAIN", "value"),
        EnvVar("EQUALS", "a=b=c"),
        EnvVar("URL", "postgres://u:p@db/x?sslmode=require&a=1"),
        EnvVar("DOUBLE", "say \"hi\""),
        EnvVar("SINGLE", "it's"),
        EnvVar("BOTH", "'\"'"),
        EnvVar("HASH", "a#b"),
        EnvVar("SPACED_HASH", "a #b"),
        EnvVar("HASH_START", "#notacomment"),
        EnvVar("EMPTY", ""),
        EnvVar("LEADING", "  lead"),
        EnvVar("TRAILING", "trail  "),
        EnvVar("ONLY_SPACES", "   "),
        EnvVar("BACKSLASH", "C:\\path\\n"),
        EnvVar("NEWLINE", "line1\nline2"),
        EnvVar("TAB", "a\tb"),
        EnvVar("DOLLAR", "\${HOME}"),
        EnvVar("UNICODE", "café ☕"),
    )

    @Test
    fun trickyValuesRoundTripBetweenRowsAndBlock() {
        val (parsed, errors) = parseDotEnv(formatDotEnv(tricky))
        assertEquals(emptyList(), errors)
        assertEquals(tricky, parsed)
    }

    @Test
    fun formatLeavesSimpleValuesUnquoted() {
        assertEquals(
            "PLAIN=value\nEQUALS=a=b=c\nHASH=a#b\nEMPTY=\n",
            formatDotEnv(listOf(EnvVar("PLAIN", "value"), EnvVar("EQUALS", "a=b=c"), EnvVar("HASH", "a#b"), EnvVar("EMPTY", ""))),
        )
        assertEquals("", formatDotEnv(emptyList()))
    }

    @Test
    fun parsesCommonDotEnvSyntax() {
        val text = """
            # a comment
              # an indented comment

            A=1
            B = spaced around
            C=x=y
            D="quoted # not a comment" # a comment
            E='single \n stays'
            F=value # inline comment
            G=a#b
            H=
            I=""
        """.trimIndent()
        val (env, errors) = parseDotEnv(text)
        assertEquals(emptyList(), errors)
        assertEquals(
            listOf(
                EnvVar("A", "1"),
                EnvVar("B", "spaced around"),
                EnvVar("C", "x=y"),
                EnvVar("D", "quoted # not a comment"),
                EnvVar("E", "single \\n stays"),
                EnvVar("F", "value"),
                EnvVar("G", "a#b"),
                EnvVar("H", ""),
                EnvVar("I", ""),
            ),
            env,
        )
    }

    @Test
    fun crlfBlocksParseLikeLf() {
        assertEquals(parseDotEnv("A=1\nB=\"x\"\n"), parseDotEnv("A=1\r\nB=\"x\"\r\n"))
    }

    @Test
    fun badLinesAreReportedByLineNumber() {
        val (env, errors) = parseDotEnv("A=1\nNOEQUALS\n=nameless\nB=\"open\nC='open\nD=\"x\" trailing\nE F=1\nG=2\n")
        assertEquals(listOf(EnvVar("A", "1"), EnvVar("G", "2")), env)
        assertEquals(6, errors.size, errors.toString())
        listOf(2, 3, 4, 5, 6, 7).forEachIndexed { i, line -> assertTrue(errors[i].startsWith("Line $line:"), errors[i]) }
    }

    @Test
    fun duplicateAndInvalidNamesAreErrors() {
        assertEquals(emptyList(), envErrors(listOf(EnvVar("A", "1"), EnvVar("B", "1"))))
        assertEquals(listOf("Duplicate name: A"), envErrors(listOf(EnvVar("A", "1"), EnvVar("B", ""), EnvVar("A", "2"))))
        // Case matters to Docker: a and A are different variables.
        assertEquals(emptyList(), envErrors(listOf(EnvVar("a", "1"), EnvVar("A", "1"))))
        assertEquals(1, envErrors(listOf(EnvVar("", "1"))).size)
        assertEquals(1, envErrors(listOf(EnvVar("A B", "1"))).size)
        assertEquals(1, envErrors(listOf(EnvVar("A=B", "1"))).size)
        assertEquals(1, envErrors(listOf(EnvVar("#A", "1"))).size)
    }
}
