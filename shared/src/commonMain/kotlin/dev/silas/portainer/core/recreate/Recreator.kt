package dev.silas.portainer.core.recreate

import dev.silas.portainer.core.api.PortainerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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

    /**
     * Cold flow: steps run on collection; completes after [RecreateEvent.Succeeded] or a [RecreateEvent.Failed].
     *
     * Collect it in a scope that outlives the UI screen (an application-level scope): cancelling the collector
     * mid-recreate stops at the current step and leaves the containers in that step's intermediate state
     * (e.g. the original renamed to `<name>-old-<ts>`). A rollback already under way always runs to completion
     * (it runs in [NonCancellable]), but its Failed event is not delivered to a cancelled collector.
     */
    fun run(oldId: String, plan: RecreatePlan): Flow<RecreateEvent> = flow {
        if (plan.pull) {
            val pulled = step(RecreateStep.Pull, onFailure = { RecreateEvent.Failed(RecreateStep.Pull, it.text(), rolledBack = true) }) {
                ops.pull(plan.image, plan.registryId)
            }
            if (!pulled) return@flow
        }

        val epoch = nowEpochSeconds()
        val tmpName = "${plan.originalName}-old-$epoch"
        val renamed = step(RecreateStep.RenameOld, onFailure = { RecreateEvent.Failed(RecreateStep.RenameOld, it.text(), rolledBack = true) }) {
            ops.rename(oldId, tmpName)
        }
        if (!renamed) return@flow

        // Create before stop: the new container must reference the old one's anonymous volumes while the old one
        // still exists. For AutoRemove containers Docker then can't delete those volumes on stop (they're in use)
        // instead of deleting the data.
        val restore = Restore(oldId, plan, tmpName, failedName = "${plan.originalName}-failed-$epoch")
        var newId = ""
        val created = step(RecreateStep.CreateNew, onFailure = { restore.beforeStop(RecreateStep.CreateNew, it.text(), newId = null) }) {
            newId = ops.create(plan.containerName, plan.createBody)
        }
        if (!created) return@flow

        // Connecting a created-but-not-started container only records the config (no IP until start),
        // so static IPs don't clash with the still-running old container.
        if (plan.extraNetworks.isNotEmpty()) {
            val connected = step(RecreateStep.ConnectNetworks, onFailure = { restore.beforeStop(RecreateStep.ConnectNetworks, it.text(), newId) }) {
                plan.extraNetworks.forEach { (network, config) -> ops.connect(network, newId, config) }
            }
            if (!connected) return@flow
        }

        if (plan.wasRunning) {
            // A lost connection leaves the stop's outcome unknown, so nothing destructive is attempted.
            val stopped = step(RecreateStep.StopOld, onFailure = {
                if (it is PortainerException.Unreachable) {
                    RecreateEvent.Failed(
                        RecreateStep.StopOld,
                        "Lost connection while stopping the old container. Nothing was removed: " +
                            "the original is $tmpName, the new one is ${plan.containerName} (not started).",
                        rolledBack = false,
                    )
                } else {
                    restore.afterStop(RecreateStep.StopOld, it.text(), newId)
                }
            }) {
                ops.stop(oldId)
            }
            if (!stopped) return@flow
            val started = step(RecreateStep.StartNew, onFailure = { restore.afterStop(RecreateStep.StartNew, it.text(), newId) }) {
                ops.start(newId)
            }
            if (!started) return@flow
        }

        // The new container is in place; a failure here leaves a `<name>-old-<ts>` container behind, nothing to undo.
        val removed = step(RecreateStep.RemoveOld, onFailure = { RecreateEvent.Failed(RecreateStep.RemoveOld, it.text(), rolledBack = false) }) {
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
        onFailure: suspend (Exception) -> RecreateEvent.Failed,
        block: () -> Unit,
    ): Boolean {
        emit(RecreateEvent.StepStarted(step))
        val error = attempt(block)
        if (error != null) {
            emit(onFailure(error))
            return false
        }
        emit(RecreateEvent.StepDone(step))
        return true
    }

    /**
     * Undoes a failed recreate. Runs in [NonCancellable] so a cancelled collector can't stop a rollback halfway.
     * A container is only removed once the other one is confirmed in place; when anything is left over,
     * `rolledBack` is false and the message names it.
     */
    private inner class Restore(val oldId: String, val plan: RecreatePlan, val tmpName: String, val failedName: String) {
        /** The new container's name. */
        private val name get() = plan.containerName

        /** The original's name: what a rollback renames it back to. */
        private val originalName get() = plan.originalName

        /** The old container was never stopped: remove the new one (if created) and rename the old one back. */
        suspend fun beforeStop(step: RecreateStep, message: String, newId: String?): RecreateEvent.Failed =
            withContext(NonCancellable) {
                val problems = mutableListOf<String>()
                if (newId != null && attempt { ops.remove(newId, force = true) } != null) {
                    problems += "Removing the new container failed; it is still there as $name."
                }
                if (attempt { ops.rename(oldId, originalName) } != null) {
                    problems += "Renaming the original container back failed; it is named $tmpName."
                }
                failed(step, message, problems)
            }

        /**
         * The old container is stopped or may be gone: Docker's auto-remove runs asynchronously after it exits, so
         * it can vanish even after a successful rename. Order: rename new aside, rename old back, start old, and
         * only then remove the new one. A renamed Replace's new container doesn't hold the original name, so it
         * isn't renamed aside: the old one goes straight back to its name.
         */
        suspend fun afterStop(step: RecreateStep, message: String, newId: String): RecreateEvent.Failed =
            withContext(NonCancellable) { restoreAfterStop(step, message, newId) }

        private suspend fun restoreAfterStop(step: RecreateStep, message: String, newId: String): RecreateEvent.Failed {
            val holdsOriginalName = name == originalName
            if (holdsOriginalName && attempt { ops.rename(newId, failedName) } != null) {
                // The original name is still held by the new container, so the old one can't get it back.
                return failed(
                    step,
                    message,
                    listOf("Renaming the new container aside failed. Nothing was removed: the new container is $name (not started) and the original is $tmpName."),
                )
            }
            val newName = if (holdsOriginalName) failedName else name
            val renameOldError = attempt { ops.rename(oldId, originalName) }
            if (renameOldError is PortainerException.NotFound) return keepNew(step, message, newId, newName)
            val startOldError = if (plan.wasRunning) attempt { ops.start(oldId) } else null
            if (startOldError is PortainerException.NotFound) return keepNew(step, message, newId, newName)

            if (renameOldError != null || startOldError != null) {
                val oldName = if (renameOldError == null) originalName else tmpName
                val problems = mutableListOf<String>()
                if (renameOldError != null) problems += "Renaming the original container back failed; it is named $tmpName."
                if (startOldError != null) problems += "Starting the original container failed; it is stopped and named $oldName."
                problems += "The new container was kept as $newName (not running)."
                return failed(step, message, problems)
            }
            if (attempt { ops.remove(newId, force = true) } != null) {
                return failed(step, message, listOf("Removing the new container failed; it is still there as $newName."))
            }
            return RecreateEvent.Failed(step, message, rolledBack = true)
        }

        /** The old container is gone: give the new one (now named [current]) its name back and keep it. */
        private suspend fun keepNew(step: RecreateStep, message: String, newId: String, current: String): RecreateEvent.Failed {
            val keptAs = if (current == name || attempt { ops.rename(newId, name) } == null) name else current
            val gone = if (RecreateWarning.AutoRemove in plan.warnings) {
                "The old container was removed by Docker (auto-remove), so it can't be restored."
            } else {
                "The old container no longer exists, so it can't be restored."
            }
            return failed(step, message, listOf("$gone The new container was kept as $keptAs (not running) so you can fix and start it."))
        }

        private fun failed(step: RecreateStep, message: String, problems: List<String>) =
            if (problems.isEmpty()) {
                RecreateEvent.Failed(step, message, rolledBack = true)
            } else {
                RecreateEvent.Failed(step, "${message.trimEnd('.')}. ${problems.joinToString(" ")}", rolledBack = false)
            }
    }
}

/**
 * Runs [block]; returns the failure, or null on success.
 *
 * A [CancellationException] is rethrown only when this coroutine itself is cancelled. One thrown by an op while
 * the coroutine is still active (e.g. its HTTP engine was closed) is a failed op, reported as a lost connection,
 * so the usual rollback runs instead of the recreate stopping halfway.
 */
private suspend inline fun attempt(block: () -> Unit): Exception? = try {
    block()
    null
} catch (e: CancellationException) {
    if (!currentCoroutineContext().isActive) throw e
    PortainerException.Unreachable("Lost connection to Portainer")
} catch (e: Exception) {
    e
}

private fun Throwable.text(): String = message?.takeIf { it.isNotBlank() } ?: "Unexpected error"
