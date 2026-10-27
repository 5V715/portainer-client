package dev.silas.portainer.app.support.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditOpsTest {

    private fun EditorText.selected() = text.substring(selection.first, selection.last + 1)

    @Test
    fun indentSingleLineKeepsCaretOnTheSameText() {
        val t = EditorText("a: 1\nb: 2", 6 until 6) // caret before ":" on line 2
        val r = indent(t)
        assertEquals("a: 1\n  b: 2", r.text)
        assertEquals(8 until 8, r.selection)
        assertEquals(':', r.text[r.selection.first])
    }

    @Test
    fun indentMultiLineSelectionKeepsSelectedText() {
        val text = "a:\n  b: 1\n  c: 2\nd: 3"
        val start = text.indexOf("b: 1")
        val end = text.indexOf("c: 2") + 4
        val r = indent(EditorText(text, start until end))
        assertEquals("a:\n    b: 1\n    c: 2\nd: 3", r.text)
        assertEquals("b: 1\n    c: 2", r.selected())
    }

    @Test
    fun indentIgnoresTheLineWhereASelectionEndsAtColumnZero() {
        val text = "a\nb\nc"
        val r = indent(EditorText(text, 0 until 4)) // "a\nb\n"
        assertEquals("  a\n  b\nc", r.text)
        assertEquals("a\n  b\n", r.selected())
    }

    @Test
    fun insertTabPutsTwoSpacesAtTheCaretOrIndentsASelection() {
        assertEquals(EditorText("ab  c", 4 until 4), insertTab(EditorText("abc", 2 until 2)))
        assertEquals("  ab\n  c", insertTab(EditorText("ab\nc", 1 until 4)).text)
    }

    @Test
    fun outdentSingleLine() {
        val r = outdent(EditorText("a:\n    b: 1", 8 until 8)) // caret before ":"
        assertEquals("a:\n  b: 1", r.text)
        assertEquals(6 until 6, r.selection)
        assertEquals(':', r.text[r.selection.first])
        // Only one leading space: remove it. None: unchanged.
        assertEquals("b", outdent(EditorText(" b", 2 until 2)).text)
        val none = EditorText("b", 0 until 0)
        assertEquals(none, outdent(none))
    }

    @Test
    fun outdentMultiLineSelectionKeepsSelectedText() {
        val text = "    a: 1\n  b: 2\nc: 3"
        val start = text.indexOf("a")
        val end = text.indexOf("c") + 1
        val r = outdent(EditorText(text, start until end))
        assertEquals("  a: 1\nb: 2\nc: 3", r.text)
        assertEquals("a: 1\nb: 2\nc", r.selected())
    }

    @Test
    fun outdentCaretInsideTheRemovedIndentMovesToLineStart() {
        val r = outdent(EditorText("x\n    y", 3 until 3)) // caret after the first indent space
        assertEquals("x\n  y", r.text)
        assertEquals(2 until 2, r.selection)
    }

    @Test
    fun indentLeavesEmptyLinesOfAMultiLineSelectionEmpty() {
        val text = "a:\n\n  b: 1\n"
        val r = indent(EditorText(text, 0 until text.length))
        assertEquals("  a:\n\n    b: 1\n", r.text)
        assertEquals("a:\n\n    b: 1\n", r.selected())
        // A caret on an empty line still indents it.
        assertEquals("a\n  \nb", indent(EditorText("a\n\nb", 2 until 2)).text)
    }

    @Test
    fun resetStartsANewDocumentWithNothingToUndo() {
        val h = EditHistory()
        h.push(EditorText("stack A", 0 until 0))
        h.push(EditorText("stack A edited", 14 until 14))
        h.reset(EditorText("stack B", 0 until 0))
        assertNull(h.undo())
        assertNull(h.redo())
        h.push(EditorText("stack B edited", 14 until 14))
        assertEquals(EditorText("stack B", 0 until 0), h.undo())
        assertNull(h.undo())
    }

    @Test
    fun typingARepeatedLetterIsPlacedAtTheCaret() {
        val h = EditHistory()
        h.push(EditorText("ab", 1 until 1))
        h.push(EditorText("abb", 2 until 2)) // "b" typed at 1 (the common prefix would say 2)
        h.push(EditorText("abxb", 3 until 3)) // continues the same word at 2
        assertEquals("ab", h.undo()?.text)
        assertNull(h.undo())
    }

    @Test
    fun undoRedoRoundTrip() {
        val h = EditHistory()
        val a = EditorText("a: 1", 4 until 4)
        val b = EditorText("a: 1\nb: 2", 9 until 9)
        val c = EditorText("a: 1\nb: 2\nc: 3", 14 until 14)
        listOf(a, b, c).forEach(h::push)
        assertEquals(b, h.undo())
        assertEquals(a, h.undo())
        assertNull(h.undo())
        assertEquals(b, h.redo())
        assertEquals(c, h.redo())
        assertNull(h.redo())
    }

    @Test
    fun pushAfterUndoDropsTheRedoBranch() {
        val h = EditHistory()
        h.push(EditorText("one", 3 until 3))
        h.push(EditorText("one two", 7 until 7))
        h.undo()
        h.push(EditorText("one three", 9 until 9))
        assertNull(h.redo())
        assertEquals(EditorText("one", 3 until 3), h.undo())
    }

    @Test
    fun pushingTheCurrentTextAgainOnlyUpdatesTheSelection() {
        val h = EditHistory()
        h.push(EditorText("abc", 0 until 0))
        h.push(EditorText("abc def", 7 until 7))
        h.push(EditorText("abc def", 2 until 2))
        assertEquals(EditorText("abc", 0 until 0), h.undo())
        assertEquals(EditorText("abc def", 2 until 2), h.redo())
    }

    @Test
    fun historyKeepsAtMostLimitUndoSteps() {
        val h = EditHistory(limit = 5)
        (0..9).forEach { h.push(EditorText("line $it\n".repeat(it + 1), 0 until 0)) }
        val undone = generateSequence { h.undo() }.toList()
        assertEquals(5, undone.size)
        assertEquals("line 4\n".repeat(5), undone.last().text)
    }

    @Test
    fun typingAWordIsOneUndoStep() {
        val h = EditHistory()
        h.push(EditorText("", 0 until 0))
        typeInto(h, "", "image")
        assertEquals(EditorText("", 0 until 0), h.undo())
        assertNull(h.undo())
    }

    @Test
    fun typingGroupsBreakAtWordsAndNewlines() {
        val h = EditHistory()
        h.push(EditorText("", 0 until 0))
        val end = typeInto(h, "", "image: nginx\nports")
        assertEquals("image: nginx\nports", end)
        assertEquals("image: nginx\n", h.undo()?.text) // "ports"
        assertEquals("image: nginx", h.undo()?.text) // "\n"
        assertEquals("image: ", h.undo()?.text) // "nginx"
        assertEquals("", h.undo()?.text) // "image: " (a word and the whitespace after it)
        assertNull(h.undo())
    }

    @Test
    fun backspacesGroupAndAnUndoBreaksTheGroup() {
        val h = EditHistory()
        h.push(EditorText("hello", 5 until 5))
        h.push(EditorText("hell", 4 until 4))
        h.push(EditorText("hel", 3 until 3))
        h.push(EditorText("he", 2 until 2))
        assertEquals("hello", h.undo()?.text)
        assertEquals("he", h.redo()?.text)
        // Typing after a jump (caret moved elsewhere) starts a new group.
        h.push(EditorText("he", 0 until 0))
        h.push(EditorText("Xhe", 1 until 1))
        h.push(EditorText("Xhe!", 4 until 4))
        assertEquals("Xhe", h.undo()?.text)
        assertEquals("he", h.undo()?.text)
    }

    @Test
    fun aPasteIsItsOwnStep() {
        val h = EditHistory()
        h.push(EditorText("", 0 until 0))
        typeInto(h, "", "ab")
        h.push(EditorText("abPASTED", 8 until 8))
        assertEquals("ab", h.undo()?.text)
    }

    @Test
    fun findAllIsCaseInsensitiveByDefaultAndNonOverlapping() {
        assertEquals(listOf(0..4, 11..15), findAll("Image: x\n  image", "IMAGE"))
        assertEquals(listOf(0..1, 2..3), findAll("aaaaa", "aa"))
        assertEquals(listOf(6..10), findAll("Image image", "image", ignoreCase = false))
        assertEquals(emptyList(), findAll("abc", ""))
        assertEquals(emptyList(), findAll("abc", "zz"))
    }

    @Test
    fun matchNavigationWrapsAround() {
        val matches = listOf(2..3, 10..11, 20..21)
        assertEquals(0, nextMatchIndex(matches, caret = 0))
        assertEquals(1, nextMatchIndex(matches, caret = 2)) // already on match 0 → next
        assertEquals(0, nextMatchIndex(matches, caret = 25))
        assertEquals(2, previousMatchIndex(matches, caret = 0))
        assertEquals(0, previousMatchIndex(matches, caret = 10))
        assertEquals(null, nextMatchIndex(emptyList(), caret = 0))
        assertEquals(null, previousMatchIndex(emptyList(), caret = 0))
    }

    @Test
    fun lineOfOffset() {
        val text = "a\nbb\n\nc"
        assertEquals(1, lineOf(text, 0))
        assertEquals(2, lineOf(text, 2))
        assertEquals(3, lineOf(text, 5))
        assertEquals(4, lineOf(text, 6))
        assertEquals(4, lineCount(text))
        assertEquals(1, lineCount(""))
    }

    /** Types [typed] one character at a time after [start], pushing each state; returns the final text. */
    private fun typeInto(h: EditHistory, start: String, typed: String): String {
        var text = start
        typed.forEach { ch ->
            text += ch
            h.push(EditorText(text, text.length until text.length))
        }
        return text
    }
}
