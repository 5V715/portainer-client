package dev.silas.portainer.app.containers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.api.removeContainer
import dev.silas.portainer.core.api.restartContainer
import dev.silas.portainer.core.api.startContainer
import dev.silas.portainer.core.api.stopContainer
import dev.silas.portainer.core.containers.ContainerFilter
import dev.silas.portainer.core.containers.applyFilter
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.recreate.isProtectedImage
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

data class ContainerListState(
    val items: List<ContainerSummary> = emptyList(),
    val filter: ContainerFilter = ContainerFilter(),
    val loading: Boolean = true,
    val error: String? = null,
    val busyIds: Set<String> = emptySet(),
) {
    val visible: List<ContainerSummary> get() = items.applyFilter(filter)
    val stacks: List<String> get() = items.mapNotNull { it.stack }.distinct().sorted()
}

class ContainerListViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(ContainerListState())
    val state: StateFlow<ContainerListState> = _state

    private var loadJob: Job? = null

    init {
        refresh()
    }

    /** Starts a load, cancelling any load still in flight so an older response can't overwrite newer data. */
    fun refresh() {
        _state.update { it.copy(loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    fun setFilter(filter: ContainerFilter) = _state.update { it.copy(filter = filter) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun start(id: String) = act(id) { startContainer(endpointId, id) }

    fun restart(id: String) = act(id) { restartContainer(endpointId, id) }

    fun stop(id: String) = act(id, guardProtected = true) { stopContainer(endpointId, id) }

    /** Never forces: a running container makes Docker answer 409, which is shown as the error. */
    fun remove(id: String) = act(id, guardProtected = true) { removeContainer(endpointId, id, force = false) }

    private fun act(id: String, guardProtected: Boolean = false, call: suspend PortainerClient.() -> Unit) {
        val target = _state.value.items.firstOrNull { it.id == id } ?: return
        if (id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + id) }
        viewModelScope.launch {
            try {
                if (guardProtected && isProtected(target)) {
                    _state.update { it.copy(error = PROTECTED_IMAGE_MESSAGE) }
                    return@launch
                }
                if (guard { container.client(serverId).call() }) refresh()
            } finally {
                _state.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    /**
     * The list endpoint reports an image ID instead of the reference once a tag has moved, so for those
     * the container is inspected. If that fails the action is refused (returns true, with the error set).
     */
    private suspend fun isProtected(target: ContainerSummary): Boolean {
        if (!looksLikeImageId(target.image)) return isProtectedImage(target.image)
        var image: String? = null
        val ok = guard {
            val config = container.client(serverId).inspectContainer(endpointId, target.id)["Config"] as? JsonObject
            image = (config?.get("Image") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        return !ok || image?.let(::isProtectedImage) ?: true
    }

    private fun looksLikeImageId(image: String) =
        image.startsWith("sha256:") || (image.length == 64 && image.all { it in '0'..'9' || it in 'a'..'f' })

    private suspend fun load() {
        guard {
            val items = container.client(serverId).containers(endpointId)
            _state.update { s ->
                val stacks = items.mapNotNull { it.stack }.toSet()
                val filter = if (s.filter.stack != null && s.filter.stack !in stacks) s.filter.copy(stack = null) else s.filter
                s.copy(items = items, filter = filter)
            }
        }
        _state.update { it.copy(loading = false) }
    }

    /** Runs [block]; failures become [ContainerListState.error]. Returns whether it succeeded. */
    private suspend fun guard(block: suspend () -> Unit): Boolean {
        val message = try {
            block()
            return true
        } catch (e: CancellationException) {
            // A client closed by invalidation cancels the call; only rethrow if this coroutine itself was cancelled.
            if (!currentCoroutineContext().isActive) throw e
            "The connection was interrupted"
        } catch (e: PortainerException) {
            e.message ?: "Request failed"
        } catch (e: IllegalStateException) {
            e.message ?: "Request failed"
        }
        _state.update { it.copy(error = message) }
        return false
    }
}

