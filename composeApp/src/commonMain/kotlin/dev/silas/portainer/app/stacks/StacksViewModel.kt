package dev.silas.portainer.app.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.images.isoDate
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.app.theme.Status
import dev.silas.portainer.app.theme.StatusTone
import dev.silas.portainer.core.api.stacks
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the stack is deployed. Upload-created stacks are web-editor stacks, as in Portainer. */
val Stack.deploymentLabel: String
    get() = when {
        control == StackControl.Limited -> "External"
        git != null -> "Git repository"
        else -> "Web editor"
    }

val Stack.controlLabel: String
    get() = when (control) {
        StackControl.Full -> "Full control"
        StackControl.Limited -> "Limited — created outside Portainer"
    }

fun stackStatus(stack: Stack): Status = when (stack.status) {
    StackStatus.Active -> Status("active", StatusTone.Good)
    StackStatus.Inactive -> Status("inactive", StatusTone.Idle)
    StackStatus.Deploying -> Status("deploying", StatusTone.Warn)
    StackStatus.Error -> Status("error", StatusTone.Bad)
}

/** `yyyy-MM-dd HH:mm UTC` from unix seconds; "unknown" when Portainer did not report the date. */
fun formatStackDate(epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds <= 0) return "unknown"
    val secondsOfDay = epochSeconds.mod(86_400L)
    val hh = (secondsOfDay / 3600).toString().padStart(2, '0')
    val mm = (secondsOfDay % 3600 / 60).toString().padStart(2, '0')
    return "${isoDate(epochSeconds)} $hh:$mm UTC"
}

data class StacksState(
    val stacks: List<Stack> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: String? = null,
) {
    val visible: List<Stack>
        get() {
            val q = query.trim()
            return stacks.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }.sortedBy { it.name.lowercase() }
        }
}

class StacksViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(StacksState())
    val state: StateFlow<StacksState> = _state

    private var loadJob: Job? = null
    private var resumedBefore = false

    init {
        refresh()
    }

    /** Starts a load, cancelling any load still in flight so an older response can't overwrite newer data. */
    fun refresh() {
        _state.update { it.copy(loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val stacks = container.client(serverId).stacks(endpointId)
                _state.update { it.copy(stacks = stacks) }
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    /** Back from the detail screen: reload (the first resume is skipped, init already loads). */
    fun onScreenResumed() {
        if (resumedBefore) refresh() else resumedBefore = true
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun clearError() = _state.update { it.copy(error = null) }
}
