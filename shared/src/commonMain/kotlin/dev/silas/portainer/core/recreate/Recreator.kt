package dev.silas.portainer.core.recreate

import dev.silas.portainer.core.api.PortainerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

enum class RecreateStep { Pull, RenameOld, CreateNew, ConnectNetworks, StopOld, StartNew, RemoveOld }

sealed interface RecreateEvent {
    data class StepStarted(val step: RecreateStep) : RecreateEvent
    data class StepDone(val step: RecreateStep) : RecreateEvent
    data class Succeeded(val newId: String) : RecreateEvent

    /** Terminal. [rolledBack] is true when the original container is back as it was. */
    data class Failed(val step: RecreateStep, val message: String, val rolledBack: Boolean) : RecreateEvent
}

private const val CONFLICT = 409

/**
 * Executes a [RecreatePlan]. The old container keeps running until the new one exists, and every
 * failure before the new container runs restores the old one, so the user never ends up with no container.
 */
class Recreator(private val ops: DockerOps, private val nowEpochSeconds: () -> Long) {

    /** Cold flow: steps run on collection; completes after [RecreateEvent.Succeeded] or a [RecreateEvent.Failed]. */
    fun run(oldId: String, plan: RecreatePlan): Flow<RecreateEvent> = flow {
        if (plan.pull) {
            val pulled = step(RecreateStep.Pull, onFailure = { RecreateEvent.Failed(RecreateStep.Pull, it, rolledBack = true) }) {
                ops.pull(plan.image, plan.registryId)
            }
            if (!pulled) return@flow
        }

        val tmpName = "${plan.containerName}-old-${nowEpochSeconds()}"
        val renamed = step(RecreateStep.RenameOld, onFailure = { RecreateEvent.Failed(RecreateStep.RenameOld, it, rolledBack = true) }) {
            ops.rename(oldId, tmpName)
        }
        if (!renamed) return@flow

        // Create before stop: the new container must reference the old one's anonymous volumes while the old one
        // still exists. For AutoRemove containers Docker then can't delete those volumes on stop (they're in use)
        // instead of deleting the data.
        val restore = Restore(oldId, plan, tmpName)
        var newId = ""
        val created = step(RecreateStep.CreateNew, onFailure = { restore(RecreateStep.CreateNew, it, newId = null, oldStopped = false) }) {
            newId = ops.create(plan.containerName, plan.createBody)
        }
        if (!created) return@flow

        // Connecting a created-but-not-started container only records the config (no IP until start),
        // so static IPs don't clash with the still-running old container.
        if (plan.extraNetworks.isNotEmpty()) {
            val connected = step(RecreateStep.ConnectNetworks, onFailure = { restore(RecreateStep.ConnectNetworks, it, newId, oldStopped = false) }) {
                plan.extraNetworks.forEach { (network, config) -> ops.connect(network, newId, config) }
            }
            if (!connected) return@flow
        }

        if (plan.wasRunning) {
            val stopped = step(RecreateStep.StopOld, onFailure = { restore(RecreateStep.StopOld, it, newId, oldStopped = true) }) {
                ops.stop(oldId)
            }
            if (!stopped) return@flow
            val started = step(RecreateStep.StartNew, onFailure = { restore(RecreateStep.StartNew, it, newId, oldStopped = true) }) {
                ops.start(newId)
            }
            if (!started) return@flow
        }

        // The new container is in place; a failure here leaves a `<name>-old-<ts>` container behind, nothing to undo.
        val removed = step(RecreateStep.RemoveOld, onFailure = { RecreateEvent.Failed(RecreateStep.RemoveOld, it, rolledBack = false) }) {
            removeOld(oldId)
        }
        if (removed) emit(RecreateEvent.Succeeded(newId))
    }

    /** Old container already gone (auto-remove after stop) counts as removed. Never removes volumes. */
    private suspend fun removeOld(oldId: String) {
        try {
            ops.remove(oldId, force = true)
        } catch (e: PortainerException.NotFound) {
            // Already removed by Docker.
        } catch (e: PortainerException.Api) {
            if (e.status != CONFLICT || e.message?.contains("already in progress") != true) throw e
        }
    }

    /** Emits StepStarted, runs [block], emits StepDone; on failure emits [onFailure]'s event and returns false. */
    private suspend inline fun FlowCollector<RecreateEvent>.step(
        step: RecreateStep,
        onFailure: suspend (String) -> RecreateEvent.Failed,
        block: () -> Unit,
    ): Boolean {
        emit(RecreateEvent.StepStarted(step))
        val error = attempt(block)
        if (error != null) {
            emit(onFailure(error.text()))
            return false
        }
        emit(RecreateEvent.StepDone(step))
        return true
    }

    private inner class Restore(val oldId: String, val plan: RecreatePlan, val tmpName: String) {
        /**
         * Removes the new container (if any), renames the old one back and, when it was stopped, starts it again.
         * Every step is attempted even if an earlier one fails; the message names anything left behind.
         */
        suspend operator fun invoke(step: RecreateStep, message: String, newId: String?, oldStopped: Boolean): RecreateEvent.Failed {
            val problems = mutableListOf<String>()
            if (newId != null && attempt { ops.remove(newId, force = true) } != null) {
                problems += "Removing the new container failed; it is still there as ${plan.containerName}."
            }
            val renameError = attempt { ops.rename(oldId, plan.containerName) }
            val oldGone = oldStopped && renameError is PortainerException.NotFound && RecreateWarning.AutoRemove in plan.warnings
            when {
                oldGone -> problems.add(0, "The old container was removed by Docker (auto-remove), so it can't be restored.")
                renameError != null -> problems += "Renaming the original container back failed; it is named $tmpName."
            }
            if (oldStopped && !oldGone && attempt { ops.start(oldId) } != null) {
                problems += "Starting the original container failed; it is stopped."
            }
            if (problems.isEmpty()) return RecreateEvent.Failed(step, message, rolledBack = true)
            return RecreateEvent.Failed(step, "${message.trimEnd('.')}. ${problems.joinToString(" ")}", rolledBack = false)
        }
    }
}

/** Runs [block]; returns the failure, or null on success. Cancellation is rethrown. */
private inline fun attempt(block: () -> Unit): Exception? = try {
    block()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e
}

private fun Throwable.text(): String = message?.takeIf { it.isNotBlank() } ?: "Unexpected error"
