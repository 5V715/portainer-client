package dev.silas.portainer.app.volumes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.api.inspectVolume
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Volume
import dev.silas.portainer.core.resources.ResourceUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Where a container mounts the volume; [readOnly] is null until the container has been inspected. */
data class VolumeMount(val path: String, val readOnly: Boolean)

data class VolumeUser(val container: ContainerSummary, val mounts: List<VolumeMount>? = null)

data class VolumeDetailState(
    val volume: Volume? = null,
    val users: List<VolumeUser> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class VolumeDetailViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val name: String,
) : ViewModel() {
    private val _state = MutableStateFlow(VolumeDetailState())
    val state: StateFlow<VolumeDetailState> = _state

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val client = container.client(serverId)
                val volume = client.inspectVolume(endpointId, name)
                val using = ResourceUsage(client.containers(endpointId)).containersUsingVolume(name)
                _state.update { it.copy(volume = volume, users = using.map { c -> VolumeUser(c) }, error = null, loading = false) }
                // The list has no mount path / read-only flag, so inspect each user (a handful at most).
                for (c in using) {
                    val mounts = try {
                        parseMounts(client.inspectContainer(endpointId, c.id), name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    _state.update { s -> s.copy(users = s.users.map { if (it.container.id == c.id) it.copy(mounts = mounts) else it }) }
                }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                _state.update { it.copy(error = "The connection was interrupted") }
            } catch (e: PortainerException) {
                _state.update { it.copy(error = e.message ?: "Request failed") }
            } catch (e: IllegalStateException) {
                _state.update { it.copy(error = e.message ?: "Request failed") }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}

/** `Mounts[]` entries of an inspected container whose `Name` is [volume]. */
internal fun parseMounts(inspect: JsonObject, volume: String): List<VolumeMount> =
    (inspect["Mounts"] as? JsonArray).orEmpty().mapNotNull { el ->
        val m = el as? JsonObject ?: return@mapNotNull null
        if ((m["Name"] as? JsonPrimitive)?.content != volume) return@mapNotNull null
        val path = (m["Destination"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val rw = (m["RW"] as? JsonPrimitive)?.booleanOrNull ?: true
        VolumeMount(path, readOnly = !rw)
    }
