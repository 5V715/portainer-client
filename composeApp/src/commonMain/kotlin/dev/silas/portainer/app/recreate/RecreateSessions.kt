package dev.silas.portainer.app.recreate

import dev.silas.portainer.app.support.safeMessage
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.recreate.PortainerDockerOps
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

/** Why a recreate can't start now: a Containers cleanup could delete the renamed original (its rollback). */
const val CLEANUP_IN_PROGRESS_MESSAGE = "A cleanup is in progress — try again when it finishes."

data class SessionKey(val serverId: String, val endpointId: Int, val containerId: String)

enum class StepStatus { Pending, Active, Done, Failed }

data class StepState(val step: RecreateStep, val status: StepStatus)

sealed interface RecreateResult {
    data class Success(val newId: String) : RecreateResult
    data class Failure(val step: RecreateStep?, val message: String, val rolledBack: Boolean) : RecreateResult
}

/** Progress of one recreate. [result] is null while it is still running. */
data class RecreateProgress(val steps: List<StepState>, val result: RecreateResult? = null, val containerName: String = "")

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
 * [cleanupRunning] tells whether a Containers cleanup runs on a server's endpoint; no recreate starts meanwhile.
 */
class RecreateSessions(
    private val scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long,
    private val cleanupRunning: (serverId: String, endpointId: Int) -> Boolean = { _, _ -> false },
) {
    private val sessions = mutableMapOf<SessionKey, MutableStateFlow<RecreateProgress?>>()

    /** Null when nothing has been started (or the last result was cleared). */
    fun progress(key: SessionKey): StateFlow<RecreateProgress?> = flowFor(key)

    /**
     * Recreates [oldId] on [key]'s endpoint through [client], which this session takes ownership of: it is
     * closed when the recreate finishes, and nothing else may close it (so a server save/delete can't cut the
     * recreate or its rollback off). Refused (false), closing [client], while one is already running or its
     * result is still uncleared, and while [refusal] gives a reason.
     */
    fun start(key: SessionKey, client: PortainerClient, oldId: String, plan: RecreatePlan): Boolean {
        val state = flowFor(key)
        if (state.value != null || refusal(key.serverId, key.endpointId) != null) {
            client.close()
            return false
        }
        state.value = RecreateProgress(plannedSteps(plan).map { StepState(it, StepStatus.Pending) }, containerName = plan.containerName)
        val recreator = Recreator(PortainerDockerOps(client, key.endpointId), nowEpochSeconds)
        scope.launch {
            try {
                recreator.run(oldId, plan).collect { event -> state.update { it?.apply(event) } }
                // The flow always ends with a terminal event; guard against one that doesn't.
                state.update { if (it != null && it.result == null) it.fail(null, "Recreate ended unexpectedly", false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it?.fail(it.steps.firstOrNull { s -> s.status == StepStatus.Active }?.step, safeMessage(e, "Unexpected error"), false) }
            } finally {
                client.close()
            }
        }
        return true
    }

    /** Why no recreate may start on [serverId]'s endpoint [endpointId] now, or null. */
    fun refusal(serverId: String, endpointId: Int): String? =
        CLEANUP_IN_PROGRESS_MESSAGE.takeIf { cleanupRunning(serverId, endpointId) }

    /** True while a recreate on [serverId]'s endpoint [endpointId] has not finished yet. */
    fun isRunning(serverId: String, endpointId: Int): Boolean = sessions.any { (key, state) ->
        key.serverId == serverId && key.endpointId == endpointId && state.value.let { it != null && it.result == null }
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
