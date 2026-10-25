package dev.silas.portainer.app.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.images
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.api.networks
import dev.silas.portainer.core.api.volumes
import dev.silas.portainer.core.cleanup.CleanupExecutor
import dev.silas.portainer.core.cleanup.CleanupItem
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.core.cleanup.CleanupPlan
import dev.silas.portainer.core.cleanup.CleanupPlanner
import dev.silas.portainer.core.cleanup.CleanupProgress
import dev.silas.portainer.core.cleanup.deleter
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.resources.ResourceProtection
import dev.silas.portainer.core.resources.ResourceUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val NAMED_VOLUMES_WARNING = "Named volumes often hold databases and app data. Everything in them is deleted permanently."

private val ID_ONLY_IMAGE = Regex("sha256:.*|[0-9a-fA-F]{64}")

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
    data class Done(val deleted: Int, val failed: Int, val reclaimedBytes: Long, val failures: List<CleanupFailure>) : CleanupPhase
}

/**
 * Previews a cleanup of one resource [kind] and runs it in [AppContainer.appScope], so closing the sheet or
 * leaving the screen never stops it. [onFinished] (the list's refresh) runs on the main thread afterwards.
 */
class CleanupViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    val kind: CleanupKind,
    private val onFinished: () -> Unit = {},
) : ViewModel() {
    private val _phase = MutableStateFlow<CleanupPhase>(CleanupPhase.Idle)
    val phase: StateFlow<CleanupPhase> = _phase

    private var loadJob: Job? = null

    /** Rebuilds the image plan from the lists already loaded (only set for images). */
    private var imagePlan: ((includeTagged: Boolean) -> CleanupPlan)? = null

    /** Builds a fresh preview; ignored while a cleanup is running. */
    fun open() {
        if (_phase.value is CleanupPhase.Running) return
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
        _phase.value = CleanupPhase.Running(0, items.size)
        container.appScope.launch {
            var client: PortainerClient? = null
            try {
                // A client of its own: saving or deleting the server can't close it mid-cleanup.
                val dedicated = container.dedicatedClient(serverId).also { client = it }
                val failures = mutableListOf<CleanupFailure>()
                var completed = 0
                CleanupExecutor(kind.deleter(dedicated, endpointId)).run(items).collect { event ->
                    when (event) {
                        is CleanupProgress.Deleted -> completed++
                        is CleanupProgress.Failed -> {
                            completed++
                            failures += CleanupFailure(event.item, event.message)
                        }
                        is CleanupProgress.Finished -> {
                            _phase.value = CleanupPhase.Done(event.deleted, event.failed, event.reclaimedBytes, failures.toList())
                            return@collect
                        }
                    }
                    _phase.value = CleanupPhase.Running(completed, items.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _phase.value = CleanupPhase.Error(safeMessage(e))
            } finally {
                client?.close()
            }
            withContext(Dispatchers.Main) { onFinished() }
        }
    }

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
        cs.filter { ID_ONLY_IMAGE.matches(it.image) }.mapNotNull { c ->
            val image = try {
                ((client.inspectContainer(endpointId, c.id)["Config"] as? JsonObject)?.get("Image") as? JsonPrimitive)?.content
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            image?.takeIf { it.isNotBlank() && !ID_ONLY_IMAGE.matches(it) }?.let { c.id to it }
        }.toMap()

    /** Rethrows only when this coroutine itself was cancelled (a closed client cancels the call too). */
    private suspend fun failureMessage(e: Exception): String =
        if (e is CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            "The connection was interrupted"
        } else {
            safeMessage(e)
        }

    private fun safeMessage(e: Exception): String = when (e) {
        is PortainerException, is IllegalStateException -> e.message ?: "Request failed"
        else -> "Request failed"
    }
}
