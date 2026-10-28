package dev.silas.portainer.core.stacks

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class ComposeAnalysisTest {
    private val valid = """
        services:
          web:
            image: nginx:1.27
          db:
            image: postgres:16
    """.trimIndent()

    @Test
    fun validFileHasImagesAndServicesInOrder() {
        val a = analyzeCompose(valid, webEditor = true)
        assertNull(a.error)
        assertEquals(listOf("nginx:1.27", "postgres:16"), a.images)
        assertEquals(listOf("web", "db"), a.services)
        assertTrue(a.warnings.isEmpty())
    }

    @Test
    fun badIndentationReportsOneBasedOffendingLine() {
        val text = "services:\n  web:\n    image: nginx\n   bad: x\n"
        val e = assertNotNull(analyzeCompose(text, webEditor = true).error)
        assertEquals(4, e.line)
        assertTrue(e.message.isNotBlank())
    }

    @Test
    fun unclosedFlowReportsLineOfUserText() {
        val e = assertNotNull(analyzeCompose("a: 1\nb: [1, 2\nc: 3\n", webEditor = true).error)
        assertEquals(3, e.line)
    }

    @Test
    fun missingServicesIsWarningNotError() {
        val a = analyzeCompose("version: '3'\nname: x\n", webEditor = true)
        assertNull(a.error)
        assertEquals(listOf<ComposeWarning>(ComposeWarning.NoServices), a.warnings)
    }

    @Test
    fun emptyTextIsNoServicesWithoutError() {
        val a = analyzeCompose("", webEditor = true)
        assertNull(a.error)
        assertEquals(listOf<ComposeWarning>(ComposeWarning.NoServices), a.warnings)
        assertEquals(emptyList(), a.images)
        assertEquals(emptyList(), a.services)
    }

    @Test
    fun serviceWithoutImageOrBuildWarns() {
        val a = analyzeCompose("services:\n  x:\n    command: ls\n  y:\n    image: a\n", webEditor = true)
        assertNull(a.error)
        assertEquals(listOf<ComposeWarning>(ComposeWarning.ServiceWithoutImage("x")), a.warnings)
        assertEquals(listOf("x", "y"), a.services)
        assertEquals(listOf("a"), a.images)
    }

    @Test
    fun buildInWebEditorWarnsAboutContext() {
        val text = "services:\n  app:\n    build: .\n  api:\n    build:\n      context: ./api\n"
        val web = analyzeCompose(text, webEditor = true)
        assertNull(web.error)
        assertEquals(
            listOf<ComposeWarning>(ComposeWarning.BuildWithoutContext("app"), ComposeWarning.BuildWithoutContext("api")),
            web.warnings,
        )
        assertEquals(emptyList(), analyzeCompose(text, webEditor = false).warnings)
    }

    @Test
    fun extendsIncludeAnchorsAndInterpolationAreAccepted() {
        val text = """
            include:
              - other.yml
            x-common: &common
              restart: always
              image: base:1
            services:
              a:
                <<: *common
              b:
                extends:
                  file: other.yml
                  service: z
              c:
                image: ${'$'}{REGISTRY}/portainer/portainer-ce
              d:
                image: ${'$'}{TAG:-latest}
              e:
                <<: *common
                image: e:2
        """.trimIndent()
        val a = analyzeCompose(text, webEditor = true)
        assertNull(a.error)
        assertEquals(emptyList(), a.warnings)
        assertEquals(listOf("a", "b", "c", "d", "e"), a.services)
        assertTrue("\${REGISTRY}/portainer/portainer-ce" in a.images)
        assertTrue("\${TAG:-latest}" in a.images)
        assertTrue("base:1" in a.images && "e:2" in a.images)
    }

    @Test
    fun aliasBombDoesNotBlowUp() {
        val sb = StringBuilder("a: &a [x, x, x, x, x, x, x, x, x]\n")
        var prev = "a"
        for (i in 1..30) {
            val n = "a$i"
            sb.append("$n: &$n [*$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev]\n")
            prev = n
        }
        val t = measureTime { analyzeCompose(sb.toString(), webEditor = true) }
        assertTrue(t.inWholeMilliseconds < 2000, "took $t")
    }

    @Test
    fun threeThousandLinesUnder500ms() {
        val text = buildString {
            append("services:\n")
            repeat(600) { i ->
                append("  s$i:\n    image: img$i:1\n    environment:\n      A: \"1\"\n      B: two # c\n")
            }
        }
        assertTrue(text.lines().size >= 3000)
        analyzeCompose(text, webEditor = true) // warm up
        val best = (1..3).minOf { measureTime { analyzeCompose(text, webEditor = true) } }
        assertTrue(best.inWholeMilliseconds < 500, "best of 3 took $best")
    }

    @Test
    fun duplicateServiceIsAnErrorAtTheDuplicate() {
        val e = assertNotNull(analyzeCompose("services:\n  web:\n    image: a\n  db:\n    image: b\n  web:\n    image: c\n", true).error)
        assertEquals(6, e.line)
        assertTrue("web" in e.message)
    }

    @Test
    fun duplicateImageInOneServiceIsAnError() {
        val e = assertNotNull(analyzeCompose("services:\n  web:\n    image: a\n    image: b\n", true).error)
        assertEquals(4, e.line)
    }

    @Test
    fun sameKeyInDifferentMappingsAndAliasReuseAreFine() {
        val text = "x: &c\n  image: a\nservices:\n  a:\n    image: a\n  b:\n    image: b\n  c: *c\n  d: *c\n"
        assertNull(analyzeCompose(text, true).error)
    }

    @Test
    fun multipleDocumentsPointAtTheExtraMarker() {
        val e = assertNotNull(analyzeCompose("a: b\n---\nc: d", webEditor = true).error)
        assertEquals("Multiple YAML documents ('---'). A compose file must contain exactly one.", e.message)
        assertEquals(2, e.line)
        val later = assertNotNull(analyzeCompose("---\nservices:\n  web:\n    image: nginx\n\n---\nx: 1\n", webEditor = true).error)
        assertEquals(6, later.line)
    }

    @Test
    fun leadingLoneDocumentMarkerIsFine() {
        val a = analyzeCompose("---\n$valid\n", webEditor = true)
        assertNull(a.error)
        assertEquals(listOf("web", "db"), a.services)
    }

    @Test
    fun blankOrNullImageIsMissing() {
        for (img in listOf("image:", "image: ''", "image: ~", "image: null")) {
            val a = analyzeCompose("services:\n  x:\n    $img\n", webEditor = true)
            assertNull(a.error, img)
            assertEquals(listOf<ComposeWarning>(ComposeWarning.ServiceWithoutImage("x")), a.warnings, img)
            assertEquals(emptyList(), a.images, img)
        }
    }

    @Test
    fun includeAloneHasNoWarnings() {
        val a = analyzeCompose("include:\n  - other.yml\n", webEditor = true)
        assertNull(a.error)
        assertTrue(a.warnings.isEmpty())
    }

    @Test
    fun extendsSuppressesMissingImageWarning() {
        val with = analyzeCompose("services:\n  b:\n    extends:\n      service: z\n", true)
        val without = analyzeCompose("services:\n  b:\n    command: ls\n", true)
        assertTrue(with.warnings.isEmpty())
        assertEquals(listOf<ComposeWarning>(ComposeWarning.ServiceWithoutImage("b")), without.warnings)
    }

    @Test
    fun neverThrowsOnRandomOrHostileInput() {
        val rnd = Random(42)
        val alphabet = "ab:- \n\t[]{}&*!|>'\"#%@`,?\${}.\u0000\u00e9"
        repeat(500) {
            val s = CharArray(rnd.nextInt(0, 80)) { alphabet[rnd.nextInt(alphabet.length)] }.concatToString()
            val a = analyzeCompose(s, webEditor = rnd.nextBoolean())
            a.error?.let { e -> assertTrue(e.line >= 1) }
        }
        listOf("[".repeat(100000), "{".repeat(50000), "- ".repeat(50000), "\uFEFF", "---\n---\n", "a: b\n---\nc: d", "services: [1, 2]", "services:\n  - web", "services:\n  web: 5", "services:\n  web:\n    image: [1]\n", "services:\n  ? [a]\n  : image: x\n")
            .forEach { analyzeCompose(it, webEditor = true) }
    }
}
