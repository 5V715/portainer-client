package dev.silas.portainer.app.recreate

import dev.silas.portainer.core.recreate.RecreateEvent
import dev.silas.portainer.core.recreate.RecreatePlan
import dev.silas.portainer.core.recreate.RecreateStep
import dev.silas.portainer.core.recreate.Recreator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionKey(val serverId: String, val endpointId: Int, val containerId: String)

enum class StepStatus { Pending, Active, Done, Failed }

data class StepState(val step: RecreateStep, val status: StepStatus)

sealed interface RecreateResult {
    data class Success(val newId: String) : RecreateResult
    data class Failure(val step: RecreateStep?, val message: String, val rolledBack: Boolean) : RecreateResult
}

/** Progress of one recreate. [result] is null while it is still running. */
data class RecreateProgress(val steps: List<StepState>, val result: RecreateResult? = null)

/** The steps [plan] will run, in order (optional ones only when they apply). */
internal fun plannedSteps(plan: RecreatePlan): List<RecreateStep> = RecreateStep.entries.filter {
    when (it) {
        RecreateStep.Pull -> plan.pull
        RecreateStep.ConnectNetworks -> plan.extraNetworks.isNotEmpty()
        RecreateStep.StopOld, RecreateStep.StartNew -> plan.wasRunning
        else -> true
    }
}

/**
 * Holds recreates in flight, keyed by container, and collects them in [scope] (an application-level scope),
 * so leaving the screen, rotating or recreating a ViewModel never cancels the operation.
 * Call from one thread (the UI thread); the progress flows themselves are safe to read anywhere.
 */
class RecreateSessions(private val scope: CoroutineScope) {
    private val sessions = mutableMapOf<SessionKey, MutableStateFlow<RecreateProgress?>>()

    /** Null when nothing has been started (or the last result was cleared). */
    fun progress(key: SessionKey): StateFlow<RecreateProgress?> = flowFor(key)

    fun isRunning(key: SessionKey): Boolean = flowFor(key).value.let { it != null && it.result == null }

    /** Starts [recreator] for [oldId]; ignored while one is already running or its result is still uncleared. */
    fun start(key: SessionKey, recreator: Recreator, oldId: String, plan: RecreatePlan) {
        val state = flowFor(key)
        if (state.value != null) return
        state.value = RecreateProgress(plannedSteps(plan).map { StepState(it, StepStatus.Pending) })
        scope.launch {
            try {
                recreator.run(oldId, plan).collect { event -> state.update { it?.apply(event) } }
                // The flow always ends with a terminal event; guard against one that doesn't.
                state.update { if (it != null && it.result == null) it.fail(null, "Recreate ended unexpectedly", false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it?.fail(it.steps.firstOrNull { s -> s.status == StepStatus.Active }?.step, e.message ?: "Unexpected error", false) }
            }
        }
    }

    /** Forgets a finished result (never a running one). */
    fun clear(key: SessionKey) {
        val state = sessions[key] ?: return
        if (state.value?.result != null) state.value = null
    }

    private fun flowFor(key: SessionKey) = sessions.getOrPut(key) { MutableStateFlow(null) }
}

private fun RecreateProgress.withStatus(step: RecreateStep, status: StepStatus) =
    copy(steps = steps.map { if (it.step == step) it.copy(status = status) else it })

private fun RecreateProgress.fail(step: RecreateStep?, message: String, rolledBack: Boolean) = copy(
    steps = if (step == null) steps else steps.map { if (it.step == step) it.copy(status = StepStatus.Failed) else it },
    result = RecreateResult.Failure(step, message, rolledBack),
)

private fun RecreateProgress.apply(event: RecreateEvent): RecreateProgress = when (event) {
    is RecreateEvent.StepStarted -> withStatus(event.step, StepStatus.Active)
    is RecreateEvent.StepDone -> withStatus(event.step, StepStatus.Done)
    is RecreateEvent.Succeeded -> copy(result = RecreateResult.Success(event.newId))
    is RecreateEvent.Failed -> fail(event.step, event.message, event.rolledBack)
}
