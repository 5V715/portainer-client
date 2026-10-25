package dev.silas.portainer.app.networks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.inspectNetwork
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.resources.ResourceUsage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A container attached to the network; [running] is false for stopped ones, which Docker's inspect leaves out (no IPs). */
data class ConnectedContainer(val id: String, val name: String, val running: Boolean, val ipv4: String?, val ipv6: String?)

data class NetworkDetailState(
    val network: Network? = null,
    /** Every container (running or stopped) using the network, by name. */
    val connected: List<ConnectedContainer> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class NetworkDetailViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val networkId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(NetworkDetailState())
    val state: StateFlow<NetworkDetailState> = _state

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
                val network = client.inspectNetwork(endpointId, networkId)
                val connected = connectedContainers(network, client.containers(endpointId))
                _state.update { it.copy(network = network, connected = connected, error = null) }
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}

/**
 * Inspect lists only running members (with their IPs); the container list also has the stopped ones.
 * Both are merged: IPs come from the inspect where it has them.
 */
internal fun connectedContainers(network: Network, containers: List<ContainerSummary>): List<ConnectedContainer> {
    val listed = ResourceUsage(containers).containersUsingNetwork(network.name).map { c ->
        val member = network.containers[c.id]
        ConnectedContainer(c.id, c.name, c.state == ContainerState.Running || member != null, member?.ipv4, member?.ipv6)
    }
    val listedIds = listed.map { it.id }.toSet()
    val onlyInspected = network.containers.filterKeys { it !in listedIds }.map { (id, m) ->
        ConnectedContainer(id, m.name, true, m.ipv4, m.ipv6)
    }
    return (listed + onlyInspected).sortedBy { it.name.ifEmpty { it.id }.lowercase() }
}
