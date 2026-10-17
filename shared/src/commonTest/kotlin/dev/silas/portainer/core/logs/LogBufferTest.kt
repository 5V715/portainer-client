package dev.silas.portainer.core.logs

import kotlin.test.Test
import kotlin.test.assertEquals

class LogBufferTest {
    private fun line(i: Int) = LogLine(LogStream.Stdout, null, "line $i")

    @Test
    fun dropsOldestBeyondCapacity() {
        val buffer = LogBuffer()
        buffer.addAll((1..5001).map(::line))
        assertEquals(5000, buffer.lines.size)
        assertEquals("line 2", buffer.lines.first().text)
        assertEquals("line 5001", buffer.lines.last().text)
    }

    @Test
    fun clearEmpties() {
        val buffer = LogBuffer(2)
        buffer.addAll(listOf(line(1), line(2), line(3)))
        assertEquals(listOf("line 2", "line 3"), buffer.lines.map { it.text })
        buffer.clear()
        assertEquals(emptyList(), buffer.lines)
    }
}
