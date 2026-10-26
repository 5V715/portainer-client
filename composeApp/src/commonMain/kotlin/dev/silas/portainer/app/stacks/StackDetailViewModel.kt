package dev.silas.portainer.app.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.stack
import dev.silas.portainer.core.api.stackFile
import dev.silas.portainer.core.api.stacks
import dev.silas.portainer.core.api.status
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.resources.resolveIdOnlyImages
import dev.silas.portainer.core.stacks.StackFeatures
import dev.silas.portainer.core.stacks.analyzeCompose
import dev.silas.portainer.core.stacks.stackContainers
import dev.silas.portainer.core.stacks.stackFeatures
import dev.silas.portainer.core.stacks.stackProtection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StackDetailState(
    val stack: Stack? = null,
    /** Members by compose project label, running and stopped. */
    val containers: List<ContainerSummary> = emptyList(),
    /** The compose file; null for external stacks or when it could not be loaded ([fileError]). */
    val file: String? = null,
    val fileError: String? = null,
    /** The stack runs Portainer (members or compose file images): lifecycle actions are blocked. */
    val protected: Boolean = false,
    val features: StackFeatures = stackFeatures(""),
    val loading: Boolean = true,
    val error: String? = null,
)

/** One stack: a Portainer-managed one by [stackId], or an external one ([stackId] null) by [name]. */
class StackDetailViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val stackId: Int?,
    private val name: String,
) : ViewModel() {
    private val _state = MutableStateFlow(StackDetailState())
    val state: StateFlow<StackDetailState> = _state

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                load()
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun load() {
        val client = container.client(serverId)
        val stack = if (stackId != null) {
            client.stack(stackId)
        } else {
            client.stacks(endpointId).firstOrNull { it.control == StackControl.Limited && it.name == name }
                ?: throw IllegalStateException("Stack $name no longer exists")
        }
        var file: String? = null
        var fileError: String? = null
        val id = stack.id
        if (id != null) {
            try {
                file = client.stackFile(id)
            } catch (e: Exception) {
                // e.g. 409 for a git stack whose settings changed without a redeploy; the rest still shows.
                fileError = failureMessage(e, "Couldn't load the compose file")
            }
        }
        val members = stackContainers(stack.name, client.containers(endpointId)).sortedBy { it.name.lowercase() }
        val resolved = client.resolveIdOnlyImages(endpointId, members)
        val images = file?.let { text ->
            withContext(Dispatchers.Default) { analyzeCompose(text, webEditor = stack.git == null).images }
        }.orEmpty()
        val features = try {
            stackFeatures(client.status().version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stackFeatures("") // unknown version: every optional feature off
        }
        _state.update {
            it.copy(
                stack = stack,
                containers = members,
                file = file,
                fileError = fileError,
                protected = stackProtection(members, images, resolved),
                features = features,
                error = null,
            )
        }
    }
}
