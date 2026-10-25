package dev.silas.portainer.app.networks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.inspectNetwork
import dev.silas.portainer.core.model.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NetworkDetailState(
    val network: Network? = null,
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
                val network = container.client(serverId).inspectNetwork(endpointId, networkId)
                _state.update { it.copy(network = network, error = null) }
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
