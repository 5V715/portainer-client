package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.support.safeMessage
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.deleteStack
import dev.silas.portainer.core.api.stack
import dev.silas.portainer.core.api.startStack
import dev.silas.portainer.core.api.stopStack
import dev.silas.portainer.core.model.StackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Why recreates, cleanups and editor deploys can't start on an endpoint now. */
const val STACK_OPERATION_IN_PROGRESS_MESSAGE = "A stack operation is in progress — try again when it finishes."

/** The stack was still deploying when the session stopped waiting for it. */
const val STACK_STILL_WORKING_MESSAGE = "Portainer is still working on this stack — check again later"

/** One stack on one endpoint; the name is unique per endpoint (external stacks have no id). */
data class StackKey(val serverId: String, val endpointId: Int, val stackName: String)

/** A lifecycle operation on a Portainer-managed stack. CE's compose delete has no volume option. */
sealed interface StackOp {
    data object Start : StackOp
    data object Stop : StackOp
    data object Delete : StackOp
}

/**
 * [running] until the operation and its check end; then [error] is null only when Portainer confirmed the result.
 * [stackId] is the stack it ran on, so a later stack with the same name never shows it.
 */
data class StackProgress(val op: StackOp, val running: Boolean, val error: String?, val stackId: Int)

/**
 * Stack operations in flight and their results, one per [StackKey], collected in [scope] (an application-level
 * scope) so leaving the screen never stops one and a new screen re-attaches to it. Each operation gets its own
 * client from [clientFactory], closed when it ends. After the request, the stack is read back (every
 * [pollIntervalMs], for at most [pollTimeoutMs]) until Portainer is no longer deploying it, and the result is
 * only reported done when its status (or, for delete, its absence) confirms it.
 * [otherWork] says why another kind of operation (recreate, cleanup, editor deploy) on an endpoint blocks a stack
 * operation. Call from one thread (the UI thread); the progress flows are safe to read anywhere.
 */
class StackSessions(
    private val scope: CoroutineScope,
    private val clientFactory: suspend (serverId: String) -> PortainerClient,
    private val otherWork: (serverId: String, endpointId: Int) -> String? = { _, _ -> null },
    private val pollIntervalMs: Long = 2_000,
    private val pollTimeoutMs: Long = 10 * 60_000,
) {
    private val sessions = mutableMapOf<StackKey, MutableStateFlow<StackProgress?>>()

    /** Null when nothing ran for [key] (or its result was cleared). */
    fun progress(key: StackKey): StateFlow<StackProgress?> = flowFor(key)

    /**
     * Runs [op] on the stack [stackId] ([key]'s stack). Refused (false) while an operation for [key] is still
     * running or [refusal] gives a reason; a finished result is replaced.
     */
    fun start(key: StackKey, stackId: Int, op: StackOp): Boolean {
        val state = flowFor(key)
        if (state.value?.running == true || refusal(key.serverId, key.endpointId) != null) return false
        state.value = StackProgress(op, running = true, error = null, stackId = stackId)
        scope.launch {
            var client: PortainerClient? = null
            try {
                val c = clientFactory(key.serverId).also { client = it }
                run(c, stackId, key.endpointId, op)
                state.value = StackProgress(op, running = false, error = null, stackId = stackId)
            } catch (e: CancellationException) {
                state.value = StackProgress(op, running = false, error = "The stack operation was interrupted", stackId = stackId)
                throw e
            } catch (e: Exception) {
                state.value = StackProgress(op, running = false, error = safeMessage(e, "Unexpected error"), stackId = stackId)
            } finally {
                client?.close()
            }
        }
        return true
    }

    /** Why no stack operation may start on [serverId]'s endpoint [endpointId] now, or null. */
    fun refusal(serverId: String, endpointId: Int): String? = otherWork(serverId, endpointId)

    /** True while a stack operation on [serverId]'s endpoint [endpointId] has not finished yet. */
    fun isRunning(serverId: String, endpointId: Int): Boolean = sessions.any { (key, state) ->
        key.serverId == serverId && key.endpointId == endpointId && state.value?.running == true
    }

    /** Forgets a finished result (never a running one). */
    fun clear(key: StackKey) {
        val state = sessions[key] ?: return
        if (state.value?.running == false) state.value = null
    }

    private fun flowFor(key: StackKey) = sessions.getOrPut(key) { MutableStateFlow(null) }

    private suspend fun run(client: PortainerClient, id: Int, endpointId: Int, op: StackOp) {
        when (op) {
            StackOp.Start -> {
                client.startStack(id, endpointId)
                awaitStatus(client, id, StackStatus.Active, "Portainer reports the stack as stopped after starting it.")
            }
            StackOp.Stop -> {
                client.stopStack(id, endpointId)
                awaitStatus(client, id, StackStatus.Inactive, "Portainer reports the stack as still running after stopping it.")
            }
            StackOp.Delete -> {
                client.deleteStack(id, endpointId)
                awaitStatus(client, id, expected = null, "Portainer still lists the stack after deleting it.")
            }
        }
    }

    /**
     * Reads the stack until it isn't deploying, then checks it is [expected] (null: gone). An error status fails
     * with Portainer's deployment message; a check that can't be made fails too: nothing is assumed done.
     */
    private suspend fun awaitStatus(client: PortainerClient, id: Int, expected: StackStatus?, mismatch: String) {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val stack = try {
                client.stack(id)
            } catch (e: PortainerException.NotFound) {
                if (expected == null) return
                throw IllegalStateException("The stack no longer exists.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Couldn't check the result: ${safeMessage(e)}")
            }
            when (stack.status) {
                StackStatus.Deploying -> {
                    if (started.elapsedNow() >= pollTimeoutMs.milliseconds) throw IllegalStateException(STACK_STILL_WORKING_MESSAGE)
                    delay(pollIntervalMs)
                }
                StackStatus.Error -> throw IllegalStateException(stack.statusMessage ?: "Portainer reports that the deployment failed.")
                expected -> return
                else -> throw IllegalStateException(mismatch)
            }
        }
    }
}
