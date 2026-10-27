package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.support.safeMessage
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.GitRedeployRequest
import dev.silas.portainer.core.api.GitSettingsRequest
import dev.silas.portainer.core.api.GitSourceAuth
import dev.silas.portainer.core.api.deleteStack
import dev.silas.portainer.core.api.detachFromGit
import dev.silas.portainer.core.api.gitRedeploy
import dev.silas.portainer.core.api.updateGitSettings
import dev.silas.portainer.core.api.stack
import dev.silas.portainer.core.api.startStack
import dev.silas.portainer.core.api.stopStack
import dev.silas.portainer.core.api.updateStack
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.Stack
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

/** An operation on a Portainer-managed stack. CE's compose delete has no volume option. */
sealed interface StackOp {
    data object Start : StackOp
    data object Stop : StackOp
    data object Delete : StackOp

    /**
     * Updates a web-editor stack with [file] and [env] (sent as given) and redeploys it. [prune] is only sent to
     * servers that have it (`updateStack`). The values stay here so a failed update can give the edits back.
     */
    data class Update(val file: String, val env: List<EnvVar>, val pullImage: Boolean, val prune: Boolean) : StackOp {
        // Env values can be secrets: never in logs or crash reports.
        override fun toString(): String = "Update(lines=${file.lines().size}, env=${env.size}, pullImage=$pullImage, prune=$prune)"
    }

    /** Saves a git stack's settings (`POST /api/stacks/{id}/git`, and its GitOps Source first when changed); no redeploy. */
    data class GitSettings(val request: GitSettingsRequest) : StackOp

    /** Pulls the repository and redeploys a git stack. */
    data class GitRedeploy(val request: GitRedeployRequest) : StackOp

    /** Turns a git stack into a web-editor stack with its current file (redeploys it); one-way. */
    data object Detach : StackOp
}

/** [op] without the git password it may carry: what progress (and so any screen) keeps after the request is sent. */
internal fun StackOp.withoutSecrets(): StackOp = when (this) {
    is StackOp.GitSettings -> {
        val r = request
        val source = r.source?.let { s ->
            val auth = s.auth
            s.copy(auth = if (auth is GitSourceAuth.Basic) GitSourceAuth.Basic(auth.auth.copy(password = null)) else auth)
        }
        StackOp.GitSettings(r.copy(auth = r.auth?.copy(password = null), source = source))
    }
    is StackOp.GitRedeploy -> StackOp.GitRedeploy(request.copy(auth = request.auth?.copy(password = null)))
    else -> this
}

/** Passwords [op] sends, to blank out of any error text. */
private fun StackOp.secrets(): List<String> = when (this) {
    is StackOp.GitSettings -> listOfNotNull(request.auth?.password, (request.source?.auth as? GitSourceAuth.Basic)?.auth?.password)
    is StackOp.GitRedeploy -> listOfNotNull(request.auth?.password)
    else -> emptyList()
}.filter { it.isNotEmpty() }

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
        // Progress outlives the request: it never holds a git password.
        val shown = op.withoutSecrets()
        state.value = StackProgress(shown, running = true, error = null, stackId = stackId)
        scope.launch {
            var client: PortainerClient? = null
            try {
                val c = clientFactory(key.serverId).also { client = it }
                run(c, stackId, key.endpointId, op)
                state.value = StackProgress(shown, running = false, error = null, stackId = stackId)
            } catch (e: CancellationException) {
                state.value = StackProgress(shown, running = false, error = "The stack operation was interrupted", stackId = stackId)
                throw e
            } catch (e: Exception) {
                val message = op.secrets().fold(safeMessage(e, "Unexpected error")) { m, secret -> m.replace(secret, "***") }
                state.value = StackProgress(shown, running = false, error = message, stackId = stackId)
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
            is StackOp.Update -> {
                client.updateStack(id, endpointId, op.file, op.env, op.pullImage, op.prune)
                awaitStatus(client, id, StackStatus.Active, "Portainer reports the stack as stopped after updating it.")
            }
            is StackOp.GitSettings -> {
                client.updateGitSettings(id, endpointId, op.request)
                awaitStatus(client, id, expected = null, mismatch = "", anyStatus = true)
            }
            is StackOp.GitRedeploy -> {
                client.gitRedeploy(id, endpointId, op.request)
                awaitStatus(client, id, StackStatus.Active, "Portainer reports the stack as stopped after redeploying it.")
            }
            StackOp.Detach -> {
                client.detachFromGit(id, endpointId)
                val stack = awaitStatus(client, id, StackStatus.Active, "Portainer reports the stack as stopped after detaching it.")
                if (stack?.git != null) throw IllegalStateException("Portainer still reports the stack as deployed from git.")
            }
        }
    }

    /**
     * Reads the stack until it isn't deploying, then checks it is [expected] (null: gone) and returns it. An error
     * status fails with Portainer's deployment message; a check that can't be made fails too: nothing is assumed done.
     * [anyStatus]: the operation deploys nothing (settings only), so the stack only has to be readable and settled.
     */
    private suspend fun awaitStatus(
        client: PortainerClient,
        id: Int,
        expected: StackStatus?,
        mismatch: String,
        anyStatus: Boolean = false,
    ): Stack? {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val stack = try {
                client.stack(id)
            } catch (e: PortainerException.NotFound) {
                if (expected == null && !anyStatus) return null
                throw IllegalStateException("The stack no longer exists.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Couldn't check the result: ${safeMessage(e)}")
            }
            when {
                stack.status == StackStatus.Deploying -> {
                    if (started.elapsedNow() >= pollTimeoutMs.milliseconds) throw IllegalStateException(STACK_STILL_WORKING_MESSAGE)
                    delay(pollIntervalMs)
                }
                anyStatus || stack.status == expected -> return stack
                stack.status == StackStatus.Error -> throw IllegalStateException(stack.statusMessage ?: "Portainer reports that the deployment failed.")
                else -> throw IllegalStateException(mismatch)
            }
        }
    }
}
