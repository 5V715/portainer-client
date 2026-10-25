package dev.silas.portainer.app.cleanup

import dev.silas.portainer.app.support.safeMessage
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.cleanup.CleanupExecutor
import dev.silas.portainer.core.cleanup.CleanupItem
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.core.cleanup.CleanupProgress
import dev.silas.portainer.core.cleanup.deleter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CleanupKey(val serverId: String, val endpointId: Int, val kind: CleanupKind)

/**
 * Running and finished cleanups, one per [CleanupKey], collected in [scope] (an application-level scope), so
 * leaving the screen or switching tabs never stops a cleanup or loses its result. Each run gets its own client
 * from [dedicatedClient] (closed when the run ends). Call from one thread (the UI thread); the phase flows
 * themselves are safe to read anywhere.
 */
class CleanupSessions(
    private val scope: CoroutineScope,
    private val dedicatedClient: suspend (serverId: String) -> PortainerClient,
) {
    private val sessions = mutableMapOf<CleanupKey, MutableStateFlow<CleanupPhase?>>()

    /** [CleanupPhase.Running] or [CleanupPhase.Done]; null when nothing ran (or the result was cleared). */
    fun phase(key: CleanupKey): StateFlow<CleanupPhase?> = flowFor(key)

    /** Deletes exactly [items], in order. Refused (false) while a cleanup for [key] is still running. */
    fun start(key: CleanupKey, items: List<CleanupItem>): Boolean {
        val state = flowFor(key)
        if (state.value is CleanupPhase.Running) return false
        state.value = CleanupPhase.Running(0, items.size)
        scope.launch {
            val deleted = mutableListOf<CleanupItem>()
            val failures = mutableListOf<CleanupFailure>()
            fun partial(error: String) = CleanupPhase.Done(
                deleted = deleted.size,
                failed = failures.size,
                reclaimedBytes = deleted.sumOf { it.sizeBytes ?: 0L },
                failures = failures.toList(),
                deletedItems = deleted.toList(),
                error = error,
            )
            var client: PortainerClient? = null
            try {
                val c = dedicatedClient(key.serverId).also { client = it }
                CleanupExecutor(key.kind.deleter(c, key.endpointId)).run(items).collect { event ->
                    when (event) {
                        is CleanupProgress.Deleted -> {
                            deleted += event.item
                            state.value = CleanupPhase.Running(deleted.size + failures.size, items.size)
                        }
                        is CleanupProgress.Failed -> {
                            failures += CleanupFailure(event.item, event.message)
                            state.value = CleanupPhase.Running(deleted.size + failures.size, items.size)
                        }
                        is CleanupProgress.Finished -> state.value = CleanupPhase.Done(
                            deleted = event.deleted,
                            failed = event.failed,
                            reclaimedBytes = event.reclaimedBytes,
                            failures = failures.toList(),
                            deletedItems = deleted.toList(),
                        )
                    }
                }
                // The executor always finishes with Finished; guard against a flow that doesn't.
                if (state.value is CleanupPhase.Running) state.value = partial("Cleanup ended unexpectedly")
            } catch (e: CancellationException) {
                state.value = partial("Cleanup was interrupted")
                throw e
            } catch (e: Exception) {
                // Keep what was already deleted or failed; the note says why the rest didn't run.
                state.value = partial(safeMessage(e))
            } finally {
                client?.close()
            }
        }
        return true
    }

    /** Forgets a finished result (never a running one). */
    fun clear(key: CleanupKey) {
        val state = sessions[key] ?: return
        if (state.value is CleanupPhase.Done) state.value = null
    }

    private fun flowFor(key: CleanupKey) = sessions.getOrPut(key) { MutableStateFlow(null) }
}
