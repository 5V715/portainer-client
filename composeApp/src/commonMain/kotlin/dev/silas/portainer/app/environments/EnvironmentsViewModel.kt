package dev.silas.portainer.app.environments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.endpoints
import dev.silas.portainer.core.model.Endpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class EnvironmentItem(val endpoint: Endpoint) {
    val enabled: Boolean get() = endpoint.type.supported
}

sealed interface EnvironmentsState {
    data object Loading : EnvironmentsState
    data class Loaded(val items: List<EnvironmentItem>) : EnvironmentsState
    data class Error(val message: String) : EnvironmentsState
}

class EnvironmentsViewModel(
    private val container: AppContainer,
    private val serverId: String,
) : ViewModel() {
    var state by mutableStateOf<EnvironmentsState>(EnvironmentsState.Loading)
        private set

    init {
        load()
    }

    fun load() {
        state = EnvironmentsState.Loading
        viewModelScope.launch {
            state = try {
                val list = container.client(serverId).endpoints()
                EnvironmentsState.Loaded(list.map(::EnvironmentItem))
            } catch (e: CancellationException) {
                // A client closed by invalidation cancels the call; only rethrow if this load itself was cancelled.
                if (!isActive) throw e
                EnvironmentsState.Error("The connection was interrupted")
            } catch (e: PortainerException) {
                EnvironmentsState.Error(e.message ?: "Could not load environments")
            } catch (e: IllegalStateException) {
                EnvironmentsState.Error(e.message ?: "Could not load environments")
            }
        }
    }

    /** Remembers the choice so the app can reopen it directly next time. */
    fun select(item: EnvironmentItem, then: (Int) -> Unit) {
        if (!item.enabled) return
        viewModelScope.launch {
            container.servers.setLastEndpoint(serverId, item.endpoint.id)
            then(item.endpoint.id)
        }
    }
}
