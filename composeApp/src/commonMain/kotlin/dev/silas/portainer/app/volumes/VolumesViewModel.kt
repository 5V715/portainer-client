package dev.silas.portainer.app.volumes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.createVolume
import dev.silas.portainer.core.api.removeVolume
import dev.silas.portainer.core.api.volumes
import dev.silas.portainer.core.model.Volume
import dev.silas.portainer.core.resources.ResourceProtection
import dev.silas.portainer.core.resources.ResourceUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val VOLUME_NOT_REMOVABLE_MESSAGE = "This volume is in use or belongs to Portainer and can't be removed."
const val COMPOSE_PROJECT_LABEL = "com.docker.compose.project"

data class VolumeItem(val volume: Volume, val used: Boolean, val protected: Boolean) {
    val unused: Boolean get() = !used
    val removable: Boolean get() = !used && !protected

    /** Anonymous volumes show only the first 12 characters of their hash name. */
    val title: String get() = if (volume.anonymous) volume.name.take(12) else volume.name
    val stack: String? get() = volume.labels[COMPOSE_PROJECT_LABEL]?.takeIf { it.isNotBlank() }
}

sealed interface CreateStatus {
    data object Idle : CreateStatus
    data object Creating : CreateStatus
    data class Done(val text: String) : CreateStatus
    data class Failed(val text: String) : CreateStatus
}

data class CreateState(val status: CreateStatus = CreateStatus.Idle)

data class VolumesState(
    val items: List<VolumeItem> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val busyNames: Set<String> = emptySet(),
    val create: CreateState = CreateState(),
) {
    val visible: List<VolumeItem>
        get() {
            val q = query.trim()
            return items.filter { q.isEmpty() || it.volume.name.contains(q, ignoreCase = true) }
                .sortedBy { it.volume.name.lowercase() }
        }
}

class VolumesViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(VolumesState())
    val state: StateFlow<VolumesState> = _state

    private var loadJob: Job? = null
    private var resumedBefore = false

    init {
        refresh()
    }

    /** Starts a load, cancelling any load still in flight so an older response can't overwrite newer data. */
    fun refresh() {
        _state.update { it.copy(loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    /** Back from the detail screen: reload (the first resume is skipped, init already loads). */
    fun onScreenResumed() {
        if (resumedBefore) refresh() else resumedBefore = true
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun load() {
        guard {
            val client = container.client(serverId)
            val volumes = client.volumes(endpointId)
            val containers = client.containers(endpointId)
            val usage = ResourceUsage(containers)
            val protection = ResourceProtection(containers)
            val items = volumes.map { VolumeItem(it, usage.isVolumeUsed(it.name), protection.isVolumeProtected(it.name)) }
            _state.update { it.copy(items = items) }
        }
        _state.update { it.copy(loading = false) }
    }

    /** Removes [name] (never forced). Used or protected volumes are refused without any API call. */
    fun remove(name: String) {
        val target = _state.value.items.firstOrNull { it.volume.name == name } ?: return
        if (!target.removable) {
            _state.update { it.copy(error = VOLUME_NOT_REMOVABLE_MESSAGE) }
            return
        }
        if (name in _state.value.busyNames) return
        _state.update { it.copy(busyNames = it.busyNames + name) }
        viewModelScope.launch {
            try {
                container.client(serverId).removeVolume(endpointId, name)
                refresh()
            } catch (e: Exception) {
                failure(e)
                // Docker refused (e.g. 409 in use): the list is probably stale, so reload it.
                if (e is PortainerException) refresh()
            } finally {
                _state.update { it.copy(busyNames = it.busyNames - name) }
            }
        }
    }

    // ---- Create ----

    fun openCreate() {
        if (_state.value.create.status != CreateStatus.Creating) _state.update { it.copy(create = CreateState()) }
    }

    /** Creates a volume; a blank [name] lets Docker choose one. Blank-key option/label rows are ignored. */
    fun create(name: String, driver: String, driverOpts: List<Pair<String, String>>, labels: List<Pair<String, String>>) {
        if (_state.value.create.status == CreateStatus.Creating) return
        _state.update { it.copy(create = CreateState(CreateStatus.Creating)) }
        viewModelScope.launch {
            val status = try {
                val created = container.client(serverId).createVolume(
                    endpointId,
                    name.trim().ifEmpty { null },
                    driver.trim().ifEmpty { "local" },
                    driverOpts.cleaned(),
                    labels.cleaned(),
                )
                CreateStatus.Done("Created ${created.name}")
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                CreateStatus.Failed("The connection was interrupted")
            } catch (e: PortainerException) {
                CreateStatus.Failed(e.message ?: "Create failed")
            } catch (e: IllegalStateException) {
                CreateStatus.Failed(e.message ?: "Create failed")
            }
            _state.update { it.copy(create = CreateState(status)) }
            if (status is CreateStatus.Done) refresh()
        }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            failure(e)
        }
    }

    private suspend fun failure(e: Exception) {
        val message = when (e) {
            is CancellationException -> {
                if (!currentCoroutineContext().isActive) throw e
                "The connection was interrupted"
            }
            is PortainerException, is IllegalStateException -> e.message ?: "Request failed"
            else -> throw e
        }
        _state.update { it.copy(error = message) }
    }
}

private fun List<Pair<String, String>>.cleaned(): Map<String, String> =
    filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second }
