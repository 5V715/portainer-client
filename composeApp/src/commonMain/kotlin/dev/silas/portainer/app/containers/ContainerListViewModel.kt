package dev.silas.portainer.app.containers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.PortainerClient
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
import dev.silas.portainer.core.resources.isIdOnlyImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

    private var resumedBefore = false

    /**
     * The screen became visible again (e.g. back from detail or recreate, where containers may have changed or
     * gone): reload. The first resume is skipped, as init already loads.
     */
    fun onScreenResumed() {
        if (resumedBefore) refresh() else resumedBefore = true
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
                if (guardProtected) {
                    when (protection(target)) {
                        Protection.Protected -> {
                            _state.update { it.copy(error = PROTECTED_IMAGE_MESSAGE) }
                            return@launch
                        }
                        Protection.Unknown -> return@launch // the inspect failure is already the error
                        Protection.Unprotected -> Unit
                    }
                }
                if (guard { container.client(serverId).call() }) refresh()
            } finally {
                _state.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    private enum class Protection { Protected, Unprotected, Unknown }

    /**
     * The list endpoint reports an image ID instead of the reference once a tag has moved, so for those
     * the container is inspected. If that fails the result is [Protection.Unknown] (the action is refused,
     * with the inspect error set).
     */
    private suspend fun protection(target: ContainerSummary): Protection {
        if (!isIdOnlyImage(target.image)) return protectionOf(isProtectedImage(target.image))
        var image: String? = null
        val ok = guard {
            val config = container.client(serverId).inspectContainer(endpointId, target.id)["Config"] as? JsonObject
            image = (config?.get("Image") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        if (!ok) return Protection.Unknown
        // No image reference to check: refuse rather than risk cutting off Portainer.
        return image?.let { protectionOf(isProtectedImage(it)) } ?: Protection.Protected
    }

    private fun protectionOf(protected: Boolean) = if (protected) Protection.Protected else Protection.Unprotected

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
        } catch (e: Exception) {
            failureMessage(e)
        }
        _state.update { it.copy(error = message) }
        return false
    }
}

