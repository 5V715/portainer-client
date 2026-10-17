package dev.silas.portainer.core.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun frame(type: Int, text: String): ByteArray {
    val payload = text.encodeToByteArray()
    val n = payload.size
    return byteArrayOf(type.toByte(), 0, 0, 0, (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + payload
}

class LogDecoderTest {
    private val ts = "2026-10-17T10:00:00.000000001Z"

    @Test
    fun singleStdoutFrame() {
        val out = LogDecoder(tty = false).feed(frame(1, "$ts hello\n"))
        assertEquals(listOf(LogLine(LogStream.Stdout, ts, "hello")), out)
    }

    @Test
    fun stderrFrame() {
        val out = LogDecoder(tty = false).feed(frame(2, "$ts oops\n"))
        assertEquals(listOf(LogLine(LogStream.Stderr, ts, "oops")), out)
    }

    @Test
    fun frameSplitAcrossThreeFeedsWithHeaderAndMultibyteSplit() {
        val bytes = frame(1, "$ts héllo\n")
        val split = bytes.indexOfFirst { it == 0xC3.toByte() } + 1 // between the two bytes of 'é'
        val decoder = LogDecoder(tty = false)
        assertTrue(decoder.feed(bytes.copyOfRange(0, 3)).isEmpty())
        assertTrue(decoder.feed(bytes.copyOfRange(3, split)).isEmpty())
        val out = decoder.feed(bytes.copyOfRange(split, bytes.size))
        assertEquals(listOf(LogLine(LogStream.Stdout, ts, "héllo")), out)
    }

    @Test
    fun twoLinesInOneFrame() {
        val out = LogDecoder(tty = false).feed(frame(1, "$ts a\n$ts b\n"))
        assertEquals(listOf("a", "b"), out.map { it.text })
    }

    @Test
    fun ttyStripsCarriageReturn() {
        val out = LogDecoder(tty = true).feed("$ts a\r\n$ts b\r\n".encodeToByteArray())
        assertEquals(listOf(LogLine(LogStream.Stdout, ts, "a"), LogLine(LogStream.Stdout, ts, "b")), out)
    }

    @Test
    fun lineWithoutNewlineAppearsOnlyAfterFlush() {
        val decoder = LogDecoder(tty = false)
        assertTrue(decoder.feed(frame(1, "$ts partial")).isEmpty())
        assertEquals(listOf(LogLine(LogStream.Stdout, ts, "partial")), decoder.flush())
        assertTrue(decoder.flush().isEmpty())
    }

    @Test
    fun lineSplitAcrossFramesIsJoined() {
        val decoder = LogDecoder(tty = false)
        assertTrue(decoder.feed(frame(1, "$ts par")).isEmpty())
        assertEquals(listOf("partial"), decoder.feed(frame(1, "tial\n")).map { it.text })
    }

    @Test
    fun lineWithoutTimestampKeepsWholeText() {
        val out = LogDecoder(tty = true).feed("just text\n".encodeToByteArray())
        assertEquals(listOf(LogLine(LogStream.Stdout, null, "just text")), out)
    }
}
