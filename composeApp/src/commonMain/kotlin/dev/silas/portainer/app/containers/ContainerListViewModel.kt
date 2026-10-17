package dev.silas.portainer.app.containers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.containers
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch { load() }
    }

    fun setFilter(filter: ContainerFilter) = _state.update { it.copy(filter = filter) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun start(id: String) = act(id) { startContainer(endpointId, id) }

    fun restart(id: String) = act(id) { restartContainer(endpointId, id) }

    fun stop(id: String) = act(id, guardProtected = true) { stopContainer(endpointId, id) }

    /** Never forces: a running container makes Docker answer 409, which is shown as the error. */
    fun remove(id: String) = act(id, guardProtected = true) { removeContainer(endpointId, id, force = false) }

    private fun act(id: String, guardProtected: Boolean = false, call: suspend PortainerClient.() -> Unit) {
        val target = _state.value.items.firstOrNull { it.id == id }
        if (guardProtected && target != null && isProtectedImage(target.image)) {
            _state.update { it.copy(error = PROTECTED_IMAGE_MESSAGE) }
            return
        }
        if (id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + id) }
        viewModelScope.launch {
            try {
                if (guard { container.client(serverId).call() }) load()
            } finally {
                _state.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    private suspend fun load() {
        guard { _state.update { s -> s.copy(items = container.client(serverId).containers(endpointId)) } }
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
