package dev.silas.portainer.app.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.logs.LogBuffer
import dev.silas.portainer.core.logs.LogLine
import dev.silas.portainer.core.logs.LogOptions
import dev.silas.portainer.core.logs.followLogs
import dev.silas.portainer.core.logs.rfc3339EpochSeconds
import dev.silas.portainer.core.platform.platformSupportsStreaming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Tail choices offered in the UI; null is "All". */
val TAIL_CHOICES: List<Int?> = listOf(100, 200, 1000, null)

data class LogsState(
    val lines: List<LogLine> = emptyList(),
    val showTimestamps: Boolean = false,
    val tail: Int? = 200,
    val query: String = "",
    val matchesOnly: Boolean = false,
    val following: Boolean = false,
    val error: String? = null,
    /** Sequence number of `lines[0]`; a line's `firstSeq + index` never changes while it stays in the buffer. */
    val firstSeq: Long = 0,
) {
    /** [lines] narrowed to those containing [query] (case-insensitive) when "matches only" is on. */
    val visible: List<LogLine>
        get() = if (matchesOnly && query.isNotEmpty()) lines.filter { it.text.contains(query, ignoreCase = true) } else lines
}

class LogsViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val containerId: String,
    private val tty: Boolean,
    private val streaming: Boolean = platformSupportsStreaming,
) : ViewModel() {
    private val _state = MutableStateFlow(LogsState())
    val state: StateFlow<LogsState> = _state

    private val buffer = LogBuffer()
    private var added = 0L
    private var job: Job? = null
    private var paused = false

    /** Timestamp of the newest line seen; resuming asks only for lines from that second and drops repeats. */
    private var lastTimestamp: String? = null

    init {
        startFresh()
    }

    fun setTail(tail: Int?) {
        if (tail == _state.value.tail) return
        _state.update { it.copy(tail = tail) }
        paused = false
        startFresh()
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setMatchesOnly(on: Boolean) = _state.update { it.copy(matchesOnly = on) }

    fun toggleTimestamps() = _state.update { it.copy(showTimestamps = !it.showTimestamps) }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Stops collecting (app backgrounded); the buffer is kept. */
    fun pause() {
        if (job == null) return
        paused = true
        stop()
    }

    /** Continues after [pause] from the last seen line; a no-op while already following. */
    fun resume() {
        if (!paused) return
        paused = false
        val since = lastTimestamp?.let(::rfc3339EpochSeconds)
        // With nothing seen yet there is nothing to continue from: start over with the chosen tail.
        if (since == null) startFresh() else start(LogOptions(tail = null, since = since), skipUpTo = lastTimestamp)
    }

    private fun startFresh() {
        stop()
        buffer.clear()
        added = 0
        lastTimestamp = null
        _state.update { it.copy(lines = emptyList(), firstSeq = 0, error = null) }
        start(LogOptions(tail = _state.value.tail))
    }

    private fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(following = false) }
    }

    /** [skipUpTo]: lines at or before this timestamp were already shown (the `since` window is whole seconds). */
    private fun start(options: LogOptions, skipUpTo: String? = null) {
        _state.update { it.copy(following = true) }
        val mine = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val message = try {
                val client = container.client(serverId)
                followLogs(client, endpointId, containerId, tty, options, streaming).collect { batch ->
                    val fresh = if (skipUpTo == null) batch else batch.filter { line -> line.timestamp.let { it == null || it > skipUpTo } }
                    fresh.mapNotNull { it.timestamp }.maxOrNull()?.let { newest ->
                        if (lastTimestamp.let { it == null || newest > it }) lastTimestamp = newest
                    }
                    if (fresh.isNotEmpty()) {
                        buffer.addAll(fresh)
                        added += fresh.size
                        val lines = buffer.lines
                        _state.update { it.copy(lines = lines, firstSeq = added - lines.size) }
                    }
                }
                null
            } catch (e: CancellationException) {
                // A client closed by invalidation cancels the call; only rethrow if this coroutine itself was cancelled.
                if (!currentCoroutineContext().isActive) throw e
                "The connection was interrupted"
            } catch (e: PortainerException) {
                e.message ?: "Request failed"
            } catch (e: IllegalStateException) {
                e.message ?: "Request failed"
            }
            // A superseded collector must not touch state that now belongs to its successor.
            if (job === coroutineContext[Job]) _state.update { it.copy(following = false, error = message ?: it.error) }
        }
        job = mine
        mine.start()
    }

    /** Public so tests can drive it; the framework calls it when the owner is destroyed. */
    public override fun onCleared() {
        job?.cancel()
    }
}
