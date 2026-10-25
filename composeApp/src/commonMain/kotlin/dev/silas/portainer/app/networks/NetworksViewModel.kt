package dev.silas.portainer.app.networks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.NetworkInput
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.createNetwork
import dev.silas.portainer.core.api.engineInfo
import dev.silas.portainer.core.api.networks
import dev.silas.portainer.core.api.removeNetwork
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.resources.ResourceProtection
import dev.silas.portainer.core.resources.ResourceUsage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val NETWORK_NOT_REMOVABLE_MESSAGE = "This network is in use, built in, or belongs to Portainer and can't be removed."

private val BASE_DRIVERS = listOf("bridge", "macvlan", "ipvlan")

data class NetworkItem(val network: Network, val containerCount: Int, val protected: Boolean) {
    val used: Boolean get() = containerCount > 0
    val unused: Boolean get() = !used
    val removable: Boolean get() = !used && !network.predefined && !protected
}

/** What the create sheet submits; text fields are trimmed by the view model. */
data class NetworkForm(
    val name: String = "",
    val driver: String = "bridge",
    val subnet: String = "",
    val gateway: String = "",
    val ipRange: String = "",
    val internal: Boolean = false,
    val attachable: Boolean = false,
    val options: List<Pair<String, String>> = emptyList(),
    val labels: List<Pair<String, String>> = emptyList(),
)

data class NetworkFormErrors(
    val name: String? = null,
    val subnet: String? = null,
    val gateway: String? = null,
    val ipRange: String? = null,
) {
    val any: Boolean get() = name != null || subnet != null || gateway != null || ipRange != null
}

fun validateNetworkForm(form: NetworkForm): NetworkFormErrors {
    val subnet = form.subnet.trim()
    val gateway = form.gateway.trim()
    val range = form.ipRange.trim()
    return NetworkFormErrors(
        name = if (form.name.isBlank()) "Name is required" else null,
        subnet = if (subnet.isNotEmpty() && !isValidCidr(subnet)) "Use CIDR, e.g. 172.20.0.0/16 or fd00::/64" else null,
        gateway = if (gateway.isNotEmpty() && !isValidIp(gateway)) "Enter a valid IPv4 or IPv6 address" else null,
        ipRange = if (range.isNotEmpty() && !isValidCidr(range)) "Use CIDR, e.g. 172.20.10.0/24" else null,
    )
}

sealed interface CreateStatus {
    data object Idle : CreateStatus
    data object Creating : CreateStatus
    data class Done(val text: String) : CreateStatus
    data class Failed(val text: String) : CreateStatus
}

data class CreateState(val status: CreateStatus = CreateStatus.Idle, val errors: NetworkFormErrors = NetworkFormErrors())

data class NetworksState(
    val items: List<NetworkItem> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val busyIds: Set<String> = emptySet(),
    val swarmActive: Boolean = false,
    /** True once the one-off engine lookup has finished, successfully or not. */
    val engineLoaded: Boolean = false,
    val create: CreateState = CreateState(),
) {
    val drivers: List<String> get() = if (swarmActive) BASE_DRIVERS + "overlay" else BASE_DRIVERS

    val visible: List<NetworkItem>
        get() {
            val q = query.trim()
            return items.filter { q.isEmpty() || it.network.name.contains(q, ignoreCase = true) }
                .sortedBy { it.network.name.lowercase() }
        }
}

class NetworksViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(NetworksState())
    val state: StateFlow<NetworksState> = _state

    private var loadJob: Job? = null
    private var resumedBefore = false

    init {
        refresh()
        loadEngine()
    }

    /** Fetched once when the tab opens; on failure overlay stays hidden and nothing is blocked. */
    private fun loadEngine() {
        viewModelScope.launch {
            val swarm = try {
                container.client(serverId).engineInfo(endpointId).swarmActive
            } catch (e: Exception) {
                failureMessage(e) // rethrows only when this coroutine was cancelled
                false
            }
            _state.update { it.copy(swarmActive = swarm, engineLoaded = true) }
        }
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
            val networks = client.networks(endpointId)
            val containers = client.containers(endpointId)
            val usage = ResourceUsage(containers)
            val protection = ResourceProtection(containers)
            val items = networks.map {
                NetworkItem(it, usage.containersUsingNetwork(it.name).size, protection.isNetworkProtected(it))
            }
            _state.update { it.copy(items = items) }
        }
        _state.update { it.copy(loading = false) }
    }

    /** Removes the network with [id]. Used, built-in or protected networks are refused without any API call. */
    fun remove(id: String) {
        val target = _state.value.items.firstOrNull { it.network.id == id } ?: return
        if (!target.removable) {
            _state.update { it.copy(error = NETWORK_NOT_REMOVABLE_MESSAGE) }
            return
        }
        if (id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + id) }
        viewModelScope.launch {
            try {
                container.client(serverId).removeNetwork(endpointId, id)
                refresh()
            } catch (e: Exception) {
                failure(e)
                // Docker refused (e.g. 409 in use): the list is probably stale, so reload it.
                if (e is PortainerException) refresh()
            } finally {
                _state.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    // ---- Create ----

    fun openCreate() {
        if (_state.value.create.status != CreateStatus.Creating) _state.update { it.copy(create = CreateState()) }
    }

    /** Validates [form] (no API call when invalid), then creates the network. Blank-key option/label rows are ignored. */
    fun create(form: NetworkForm) {
        if (_state.value.create.status == CreateStatus.Creating) return
        val errors = validateNetworkForm(form)
        if (errors.any) {
            _state.update { it.copy(create = CreateState(CreateStatus.Idle, errors)) }
            return
        }
        _state.update { it.copy(create = CreateState(CreateStatus.Creating)) }
        viewModelScope.launch {
            val status = try {
                container.client(serverId).createNetwork(
                    endpointId,
                    NetworkInput(
                        name = form.name.trim(),
                        driver = form.driver.takeIf { it in _state.value.drivers } ?: "bridge",
                        subnet = form.subnet.trim().ifEmpty { null },
                        gateway = form.gateway.trim().ifEmpty { null },
                        ipRange = form.ipRange.trim().ifEmpty { null },
                        internal = form.internal,
                        attachable = form.attachable,
                        options = form.options.cleaned(),
                        labels = form.labels.cleaned(),
                    ),
                )
                CreateStatus.Done("Created ${form.name.trim()}")
            } catch (e: Exception) {
                CreateStatus.Failed(failureMessage(e, "Create failed"))
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
        val message = failureMessage(e)
        _state.update { it.copy(error = message) }
    }
}

private fun List<Pair<String, String>>.cleaned(): Map<String, String> =
    filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second }
