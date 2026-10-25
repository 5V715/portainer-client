package dev.silas.portainer.app.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.images
import dev.silas.portainer.core.api.networks
import dev.silas.portainer.core.api.systemDf
import dev.silas.portainer.core.api.volumes
import dev.silas.portainer.core.cleanup.CleanupItem
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.core.cleanup.CleanupPlan
import dev.silas.portainer.core.cleanup.CleanupPlanner
import dev.silas.portainer.core.cleanup.recoverable
import dev.silas.portainer.core.cleanup.withVolumeSizes
import dev.silas.portainer.core.resources.ResourceProtection
import dev.silas.portainer.core.resources.ResourceUsage
import dev.silas.portainer.core.resources.resolveIdOnlyImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val RECREATE_IN_PROGRESS_MESSAGE = "A recreate is in progress — try again when it finishes."
const val NAMED_VOLUMES_WARNING = "Named volumes often hold databases and app data. Everything in them is deleted permanently."

data class CleanupFailure(val item: CleanupItem, val message: String)

sealed interface CleanupPhase {
    /** Not opened yet. */
    data object Idle : CleanupPhase
    data object Loading : CleanupPhase
    data class Error(val message: String) : CleanupPhase

    /** [includeTagged] applies to images, [includeNamed] to volumes. */
    data class Preview(
        val plan: CleanupPlan,
        val includeTagged: Boolean = false,
        val includeNamed: Boolean = false,
        /** True while the volume sizes (`system/df`) are still being fetched. */
        val sizesPending: Boolean = false,
    ) : CleanupPhase {
        /** Exactly what a confirm deletes, in order. */
        val selected: List<CleanupItem> get() = plan.toDelete + if (includeNamed) plan.namedVolumes else emptyList()
        val isEmpty: Boolean get() = plan.toDelete.isEmpty() && plan.namedVolumes.isEmpty()

        /** The grand-total line over [selected] (Images and Volumes only); null when there is nothing to show. */
        val recoverableLine: String? get() = recoverableLine(plan.kind, selected.recoverable(), sizesPending)

        /** Size of the named volumes, shown in their section; null while sizes are pending or for other kinds. */
        val namedSubtotal: String? get() = namedSubtotal(plan.namedVolumes.recoverable(), sizesPending)
    }

    /** Second confirmation before deleting named volumes; [preview] is what goes back on cancel. */
    data class ConfirmNamed(val preview: Preview) : CleanupPhase
    data class Running(val completed: Int, val total: Int) : CleanupPhase
    /** [error] says why the run stopped early; the counts and lists still hold what had happened by then. */
    data class Done(
        val deleted: Int,
        val failed: Int,
        val reclaimedBytes: Long,
        val failures: List<CleanupFailure>,
        val deletedItems: List<CleanupItem> = emptyList(),
        val error: String? = null,
    ) : CleanupPhase
}

/**
 * Previews a cleanup of one resource [kind]; the run itself belongs to [AppContainer.cleanupSessions], so closing
 * the sheet, switching tabs or leaving the screen never stops it, and a new ViewModel for the same server,
 * endpoint and kind re-attaches to it. [onFinished] (the list's refresh) runs when a finished run is seen,
 * including one that finished while the screen was gone. [sheetVisible] says whether the sheet shows; a run that
 * finishes while it is closed opens it again with the result.
 *
 * Container cleanups are refused while a recreate runs on the endpoint: mid-recreate, the new container is only
 * created and the renamed old one stopped, so both would look like stopped containers to delete.
 */
class CleanupViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    val kind: CleanupKind,
    private val onFinished: () -> Unit = {},
) : ViewModel() {
    private val key = CleanupKey(serverId, endpointId, kind)
    private val sessions = container.cleanupSessions
    private val session = sessions.phase(key)
    private val _phase = MutableStateFlow(session.value ?: CleanupPhase.Idle)
    val phase: StateFlow<CleanupPhase> = _phase

    private val _sheetVisible = MutableStateFlow(session.value is CleanupPhase.Running || session.value is CleanupPhase.Done)
    val sheetVisible: StateFlow<Boolean> = _sheetVisible

    private var loadJob: Job? = null

    /** Fetches volume sizes after the preview shows; cancelled when the sheet closes. Never blocks a cleanup. */
    private var sizesJob: Job? = null

    /** The df answer (empty after a failure); null until it lands. Kept so a run confirmed earlier still counts it. */
    private var volumeSizes: Map<String, Long?>? = null

    /** A finished volume run reports the sizes df knows, even when df landed after the run started. */
    private fun withSizes(phase: CleanupPhase): CleanupPhase {
        val sizes = volumeSizes
        if (kind != CleanupKind.Volumes || sizes == null || phase !is CleanupPhase.Done) return phase
        val items = phase.deletedItems.map { if (it.sizeBytes == null) it.copy(sizeBytes = sizes[it.id]) else it }
        return phase.copy(deletedItems = items, reclaimedBytes = items.sumOf { it.sizeBytes ?: 0L })
    }

    /** Rebuilds the image plan from the lists already loaded (only set for images). */
    private var imagePlan: ((includeTagged: Boolean) -> CleanupPlan)? = null

    init {
        viewModelScope.launch {
            var last: CleanupPhase? = null
            session.collect { run ->
                if (run is CleanupPhase.Done && last !is CleanupPhase.Done) {
                    onFinished()
                    _sheetVisible.value = true
                }
                last = run
                _phase.update { current ->
                    when {
                        run != null -> withSizes(run)
                        current is CleanupPhase.Running || current is CleanupPhase.Done -> CleanupPhase.Idle
                        else -> current
                    }
                }
            }
        }
    }

    /** Shows the sheet with a fresh preview (forgetting a finished result); only shows it while a cleanup is running. */
    fun open() {
        _sheetVisible.value = true
        if (session.value is CleanupPhase.Running) return
        sessions.clear(key)
        if (recreateBlocks()) {
            loadJob?.cancel()
            _phase.value = CleanupPhase.Error(RECREATE_IN_PROGRESS_MESSAGE)
            return
        }
        loadJob?.cancel()
        sizesJob?.cancel()
        volumeSizes = null
        _phase.value = CleanupPhase.Loading
        loadJob = viewModelScope.launch {
            val next = try {
                CleanupPhase.Preview(buildPlan(container.client(serverId)), sizesPending = kind == CleanupKind.Volumes)
            } catch (e: Exception) {
                CleanupPhase.Error(failureMessage(e))
            }
            var shown = false
            _phase.update {
                shown = it == CleanupPhase.Loading
                if (shown) next else it
            }
            if (shown && next is CleanupPhase.Preview && next.sizesPending) loadVolumeSizes()
        }
    }

    /** A failure only leaves the sizes unknown: the cleanup itself is unaffected. */
    private fun loadVolumeSizes() {
        sizesJob?.cancel()
        sizesJob = viewModelScope.launch {
            val sizes = try {
                container.client(serverId).systemDf(endpointId).volumeSizes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            volumeSizes = sizes
            // Only sizes change, never which items are selected or the toggles.
            fun CleanupPhase.Preview.sized() = copy(plan = plan.withVolumeSizes(sizes), sizesPending = false)
            _phase.update {
                when {
                    it is CleanupPhase.Preview && it.sizesPending -> it.sized()
                    it is CleanupPhase.ConfirmNamed && it.preview.sizesPending -> it.copy(preview = it.preview.sized())
                    it is CleanupPhase.Done -> withSizes(it)
                    else -> it
                }
            }
        }
    }

    fun setIncludeTagged(on: Boolean) {
        val rebuild = imagePlan ?: return
        _phase.update { if (it is CleanupPhase.Preview) it.copy(plan = rebuild(on), includeTagged = on) else it }
    }

    fun setIncludeNamed(on: Boolean) = _phase.update { if (it is CleanupPhase.Preview) it.copy(includeNamed = on) else it }

    /** Runs the preview, or asks the second confirmation first when named volumes are selected. */
    fun confirm() {
        val preview = _phase.value as? CleanupPhase.Preview ?: return
        if (preview.selected.isEmpty()) return
        if (preview.includeNamed && preview.plan.namedVolumes.isNotEmpty()) {
            _phase.value = CleanupPhase.ConfirmNamed(preview)
        } else {
            run(preview.selected)
        }
    }

    fun confirmNamed() {
        val confirm = _phase.value as? CleanupPhase.ConfirmNamed ?: return
        run(confirm.preview.selected)
    }

    fun cancelNamed() = _phase.update { if (it is CleanupPhase.ConfirmNamed) it.preview else it }

    private fun run(items: List<CleanupItem>) {
        if (recreateBlocks()) {
            _phase.value = CleanupPhase.Error(RECREATE_IN_PROGRESS_MESSAGE)
            return
        }
        // Refused while a run for this key is active (a double tap, or a run started from an earlier screen).
        if (sessions.start(key, items)) _phase.value = CleanupPhase.Running(0, items.size)
    }

    /** Closes the sheet and forgets a finished result, so it isn't shown again. A running cleanup keeps going. */
    fun acknowledge() {
        _sheetVisible.value = false
        loadJob?.cancel()
        sizesJob?.cancel()
        // A stale or still-loading preview (maybe still "calculating") is never shown again: reopening goes through open().
        _phase.update {
            if (it is CleanupPhase.Loading || it is CleanupPhase.Preview || it is CleanupPhase.ConfirmNamed) CleanupPhase.Idle else it
        }
        sessions.clear(key)
    }

    private fun recreateBlocks(): Boolean =
        kind == CleanupKind.Containers && container.recreateSessions.isRunning(serverId, endpointId)

    private suspend fun buildPlan(client: PortainerClient): CleanupPlan {
        imagePlan = null
        return when (kind) {
            CleanupKind.Containers -> {
                val cs = client.containers(endpointId)
                CleanupPlanner.containers(cs, ResourceProtection(cs, client.resolveIdOnlyImages(endpointId, cs)))
            }
            CleanupKind.Images -> {
                val images = client.images(endpointId)
                val cs = client.containers(endpointId)
                val usage = ResourceUsage(cs)
                val protection = ResourceProtection(cs, client.resolveIdOnlyImages(endpointId, cs))
                val rebuild = { tagged: Boolean -> CleanupPlanner.images(images, usage, protection, tagged) }
                imagePlan = rebuild
                rebuild(false)
            }
            CleanupKind.Volumes -> {
                val volumes = client.volumes(endpointId)
                val cs = client.containers(endpointId)
                CleanupPlanner.volumes(volumes, ResourceUsage(cs), ResourceProtection(cs))
            }
            CleanupKind.Networks -> {
                val networks = client.networks(endpointId)
                val cs = client.containers(endpointId)
                CleanupPlanner.networks(networks, ResourceUsage(cs), ResourceProtection(cs))
            }
        }
    }
}
