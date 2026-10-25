package dev.silas.portainer.app.recreate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val NOTHING_RUNNING_MESSAGE = "Nothing is being recreated for this container."

/** Where a recreate is. Running and Done mirror the app-level [RecreateSessions] entry. */
sealed interface RecreatePhase {
    data object Loading : RecreatePhase

    /** No recreate was started for this container (or its result was already acted on). */
    data class Error(val message: String) : RecreatePhase
    data class Running(val steps: List<StepState>) : RecreatePhase
    data class Done(val steps: List<StepState>, val result: RecreateResult) : RecreatePhase
}

data class RecreateState(val phase: RecreatePhase = RecreatePhase.Loading, val containerName: String = "")

/**
 * The progress of a recreate (Quick Recreate, or Replace from the editor) started in [AppContainer.recreateSessions];
 * this ViewModel only shows it, so leaving or recreating the screen never affects the operation.
 */
class RecreateViewModel(
    container: AppContainer,
    serverId: String,
    endpointId: Int,
    containerId: String,
) : ViewModel() {
    private val key = SessionKey(serverId, endpointId, containerId)
    private val sessions = container.recreateSessions
    private val _state = MutableStateFlow(RecreateState())
    val state: StateFlow<RecreateState> = _state

    init {
        val progress = sessions.progress(key)
        if (progress.value == null) _state.value = RecreateState(RecreatePhase.Error(NOTHING_RUNNING_MESSAGE))
        // A cleared result (after acknowledge) keeps showing what was there.
        viewModelScope.launch { progress.collect { p -> if (p != null) show(p) } }
    }

    private fun show(p: RecreateProgress) {
        val phase = p.result?.let { RecreatePhase.Done(p.steps, it) } ?: RecreatePhase.Running(p.steps)
        _state.update { it.copy(phase = phase, containerName = it.containerName.ifEmpty { p.containerName }) }
    }

    /** Forgets a finished result once the screen has acted on it (navigated away). */
    fun acknowledge() = sessions.clear(key)
}
