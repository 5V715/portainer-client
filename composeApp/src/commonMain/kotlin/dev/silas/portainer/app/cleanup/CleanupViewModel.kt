package dev.silas.portainer.app.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.images
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.api.networks
import dev.silas.portainer.core.api.volumes
import dev.silas.portainer.core.cleanup.CleanupItem
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.core.cleanup.CleanupPlan
import dev.silas.portainer.core.cleanup.CleanupPlanner
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.resources.ResourceProtection
import dev.silas.portainer.core.resources.ResourceUsage
import dev.silas.portainer.core.resources.isIdOnlyImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val NAMED_VOLUMES_WARNING = "Named volumes often hold databases and app data. Everything in them is deleted permanently."

data class CleanupFailure(val item: CleanupItem, val message: String)

sealed interface CleanupPhase {
    /** Not opened yet. */
    data object Idle : CleanupPhase
    data object Loading : CleanupPhase
    data class Error(val message: String) : CleanupPhase

    /** [includeTagged] applies to images, [includeNamed] to volumes. */
    data class Preview(val plan: CleanupPlan, val includeTagged: Boolean = false, val includeNamed: Boolean = false) : CleanupPhase {
        /** Exactly what a confirm deletes, in order. */
        val selected: List<CleanupItem> get() = plan.toDelete + if (includeNamed) plan.namedVolumes else emptyList()
        val isEmpty: Boolean get() = plan.toDelete.isEmpty() && plan.namedVolumes.isEmpty()
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
 * including one that finished while the screen was gone.
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

    private var loadJob: Job? = null

    /** Rebuilds the image plan from the lists already loaded (only set for images). */
    private var imagePlan: ((includeTagged: Boolean) -> CleanupPlan)? = null

    init {
        viewModelScope.launch {
            var last: CleanupPhase? = null
            session.collect { run ->
                if (run is CleanupPhase.Done && last !is CleanupPhase.Done) onFinished()
                last = run
                _phase.update { current ->
                    when {
                        run != null -> run
                        current is CleanupPhase.Running || current is CleanupPhase.Done -> CleanupPhase.Idle
                        else -> current
                    }
                }
            }
        }
    }

    /** Builds a fresh preview (forgetting a finished result); ignored while a cleanup is running. */
    fun open() {
        if (session.value is CleanupPhase.Running) return
        sessions.clear(key)
        loadJob?.cancel()
        _phase.value = CleanupPhase.Loading
        loadJob = viewModelScope.launch {
            val next = try {
                CleanupPhase.Preview(buildPlan(container.client(serverId)))
            } catch (e: Exception) {
                CleanupPhase.Error(failureMessage(e))
            }
            _phase.update { if (it == CleanupPhase.Loading) next else it }
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
        // Refused while a run for this key is active (a double tap, or a run started from an earlier screen).
        if (sessions.start(key, items)) _phase.value = CleanupPhase.Running(0, items.size)
    }

    /** Forgets a finished result once the sheet is closed, so it isn't shown again. */
    fun acknowledge() = sessions.clear(key)

    private suspend fun buildPlan(client: PortainerClient): CleanupPlan {
        imagePlan = null
        return when (kind) {
            CleanupKind.Containers -> {
                val cs = client.containers(endpointId)
                CleanupPlanner.containers(cs, ResourceProtection(cs, resolveImages(client, cs)))
            }
            CleanupKind.Images -> {
                val images = client.images(endpointId)
                val cs = client.containers(endpointId)
                val usage = ResourceUsage(cs)
                val protection = ResourceProtection(cs, resolveImages(client, cs))
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

    /**
     * `Config.Image` for every container (running or stopped) whose list image is only an id. A failed
     * inspect, or one that again shows only an id, leaves the container unresolved so the planner keeps it.
     */
    private suspend fun resolveImages(client: PortainerClient, cs: List<ContainerSummary>): Map<String, String> =
        cs.filter { isIdOnlyImage(it.image) }.mapNotNull { c ->
            val image = try {
                ((client.inspectContainer(endpointId, c.id)["Config"] as? JsonObject)?.get("Image") as? JsonPrimitive)?.content
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            image?.takeIf { it.isNotBlank() && !isIdOnlyImage(it) }?.let { c.id to it }
        }.toMap()

    /** Rethrows only when this coroutine itself was cancelled (a closed client cancels the call too). */
    private suspend fun failureMessage(e: Exception): String =
        if (e is CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            "The connection was interrupted"
        } else {
            safeMessage(e)
        }
}
