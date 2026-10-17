package dev.silas.portainer.app.registries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.deleteRegistry
import dev.silas.portainer.core.api.registries
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.model.RegistryType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun typeLabel(type: RegistryType): String = when (type) {
    RegistryType.DockerHub -> "Docker Hub"
    RegistryType.GitHub -> "GitHub (ghcr.io)"
    RegistryType.GitLab -> "GitLab"
    RegistryType.Quay -> "Quay"
    RegistryType.Azure -> "Azure"
    RegistryType.ProGet -> "ProGet"
    RegistryType.Ecr -> "AWS ECR"
    RegistryType.Custom -> "Custom"
    RegistryType.Unknown -> "Unknown"
}

fun authLabel(registry: Registry): String =
    if (registry.authentication && registry.username != null) "Authenticated as ${registry.username}" else "Anonymous"

/** Runs [block], turning API failures into a user-facing message (never including request bodies). */
internal suspend fun runGuarded(block: suspend () -> Unit): String? = try {
    block()
    null
} catch (e: CancellationException) {
    // A client closed by invalidation cancels the call; only rethrow if this coroutine itself was cancelled.
    if (!currentCoroutineContext().isActive) throw e
    "The connection was interrupted"
} catch (e: PortainerException) {
    e.message ?: "Request failed"
} catch (e: IllegalStateException) {
    e.message ?: "Request failed"
}

data class RegistriesState(
    val items: List<Registry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class RegistriesViewModel(
    private val container: AppContainer,
    private val serverId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(RegistriesState())
    val state: StateFlow<RegistriesState> = _state

    private var loadJob: Job? = null

    init {
        refresh()
    }

    /** Starts a load, cancelling any load still in flight so an older response can't overwrite newer data. */
    fun refresh() {
        _state.update { it.copy(loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val error = runGuarded {
                val items = container.client(serverId).registries()
                _state.update { it.copy(items = items) }
            }
            _state.update { it.copy(loading = false, error = error ?: it.error) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Call only after the user confirmed. */
    fun delete(id: Int) {
        viewModelScope.launch {
            val error = runGuarded { container.client(serverId).deleteRegistry(id) }
            if (error != null) _state.update { it.copy(error = error) } else refresh()
        }
    }
}
