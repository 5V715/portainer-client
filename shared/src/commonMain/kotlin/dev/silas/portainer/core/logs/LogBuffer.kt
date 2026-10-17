package dev.silas.portainer.core.logs

/** Holds the most recent [capacity] lines, dropping the oldest. */
class LogBuffer(val capacity: Int = 5000) {
    private val items = ArrayDeque<LogLine>()

    val lines: List<LogLine> get() = items.toList()

    fun addAll(new: List<LogLine>) {
        items.addAll(new)
        while (items.size > capacity) items.removeFirst()
    }

    fun clear() = items.clear()
}
