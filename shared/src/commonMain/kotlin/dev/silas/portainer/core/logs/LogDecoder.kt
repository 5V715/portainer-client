package dev.silas.portainer.core.logs

enum class LogStream { Stdout, Stderr }

data class LogLine(val stream: LogStream, val timestamp: String?, val text: String)

private const val HEADER_SIZE = 8
private const val STDERR_TYPE = 2

/**
 * Incremental decoder for Docker log bytes. Non-TTY containers use multiplexed frames
 * (byte 0 = stream, bytes 4-7 = big-endian payload length); TTY containers send raw bytes.
 * Partial frame headers, payloads and lines are kept across [feed] calls and UTF-8 is only
 * decoded on complete lines, so multibyte characters split across chunks survive.
 */
class LogDecoder(private val tty: Boolean) {
    private var pending = ByteArray(0)
    private val partial = mapOf(LogStream.Stdout to ByteBuilder(), LogStream.Stderr to ByteBuilder())

    fun feed(bytes: ByteArray): List<LogLine> {
        val out = mutableListOf<LogLine>()
        if (tty) {
            consume(LogStream.Stdout, bytes, 0, bytes.size, out)
            return out
        }
        pending += bytes
        var pos = 0
        while (pending.size - pos >= HEADER_SIZE) {
            val length = ((pending[pos + 4].toInt() and 0xFF) shl 24) or
                ((pending[pos + 5].toInt() and 0xFF) shl 16) or
                ((pending[pos + 6].toInt() and 0xFF) shl 8) or
                (pending[pos + 7].toInt() and 0xFF)
            if (length < 0) { // corrupt header: stop trusting the stream rather than buffer forever
                pending = ByteArray(0)
                return out
            }
            val payloadEnd = pos + HEADER_SIZE + length
            if (pending.size < payloadEnd) break
            val stream = if (pending[pos].toInt() == STDERR_TYPE) LogStream.Stderr else LogStream.Stdout
            consume(stream, pending, pos + HEADER_SIZE, payloadEnd, out)
            pos = payloadEnd
        }
        pending = if (pos == 0) pending else pending.copyOfRange(pos, pending.size)
        return out
    }

    /** Emits any trailing text that never got a newline. */
    fun flush(): List<LogLine> {
        pending = ByteArray(0)
        val out = mutableListOf<LogLine>()
        for ((stream, builder) in partial) {
            if (builder.size > 0) out += parseLine(stream, builder.takeAll())
        }
        return out
    }

    private fun consume(stream: LogStream, data: ByteArray, from: Int, to: Int, out: MutableList<LogLine>) {
        val builder = partial.getValue(stream)
        var start = from
        for (i in from until to) {
            if (data[i] == NEWLINE) {
                builder.append(data, start, i)
                out += parseLine(stream, builder.takeAll())
                start = i + 1
            }
        }
        builder.append(data, start, to)
    }

    private fun parseLine(stream: LogStream, raw: ByteArray): LogLine {
        var end = raw.size
        if (end > 0 && raw[end - 1] == CR) end--
        val line = raw.decodeToString(0, end)
        val space = line.indexOf(' ')
        if (space > 0 && looksLikeTimestamp(line, space)) {
            return LogLine(stream, line.substring(0, space), line.substring(space + 1))
        }
        return LogLine(stream, null, line)
    }

    private fun looksLikeTimestamp(line: String, end: Int): Boolean =
        end >= 20 && line[4] == '-' && line[7] == '-' && line[10] == 'T' && line[0].isDigit()

    private class ByteBuilder {
        private var buf = ByteArray(0)
        var size = 0
            private set

        fun append(data: ByteArray, from: Int, to: Int) {
            val n = to - from
            if (n <= 0) return
            if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
            data.copyInto(buf, size, from, to)
            size += n
        }

        fun takeAll(): ByteArray = buf.copyOf(size).also { size = 0 }
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
    }
}
