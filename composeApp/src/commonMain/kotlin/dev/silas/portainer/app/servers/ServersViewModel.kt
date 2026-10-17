package dev.silas.portainer.app.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.storage.ServerConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServersViewModel(private val container: AppContainer) : ViewModel() {
    val servers: StateFlow<List<ServerConfig>> =
        container.servers.servers.stateIn(viewModelScope, SharingStarted.Eagerly, container.servers.servers.value)

    fun delete(id: String) {
        viewModelScope.launch { container.deleteServer(id) }
    }
}
