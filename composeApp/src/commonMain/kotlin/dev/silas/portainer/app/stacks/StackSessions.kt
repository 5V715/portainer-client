package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.support.safeMessage
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.GitRedeployRequest
import dev.silas.portainer.core.api.GitSettingsRequest
import dev.silas.portainer.core.api.GitSourceAuth
import dev.silas.portainer.core.api.createStackFromRepository
import dev.silas.portainer.core.api.createStackFromText
import dev.silas.portainer.core.api.deleteStack
import dev.silas.portainer.core.api.detachFromGit
import dev.silas.portainer.core.api.gitRedeploy
import dev.silas.portainer.core.api.migrateStack
import dev.silas.portainer.core.api.stacks
import dev.silas.portainer.core.api.updateGitSettings
import dev.silas.portainer.core.api.stack
import dev.silas.portainer.core.api.startStack
import dev.silas.portainer.core.api.stopStack
import dev.silas.portainer.core.api.updateStack
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.model.StackControl
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

    /**
     * Moves the stack to the Docker environment [targetEndpointId] (under [newName] when not null). CE answers only after
     * it deployed on the target and removed the stack from the source, so a 200 naming the target is the result.
     * [targetName] and [sourceName] are for messages only.
     */
    data class Migrate(
        val targetEndpointId: Int,
        val newName: String?,
        val targetName: String? = null,
        val sourceName: String? = null,
    ) : StackOp

    /**
     * Creates the stack named by its [StackKey] (its id is known once Portainer answers) and waits for its deploy.
     * The values stay here so a failed create can give the form back.
     */
    sealed interface Create : StackOp {
        /** The id of the stack this create copies (a duplicate), so its screen can re-attach; null for a new stack. */
        val copiedFrom: Int?

        /** A web-editor stack: typed, uploaded or from a custom template. */
        data class Text(
            val file: String,
            val env: List<EnvVar>,
            val fromTemplate: Boolean = false,
            override val copiedFrom: Int? = null,
        ) : Create {
            // Env values can be secrets: never in logs or crash reports.
            override fun toString(): String = "Create.Text(lines=${file.lines().size}, env=${env.size}, fromTemplate=$fromTemplate)"
        }

        /** A git stack from [url]; [request]'s prune and source don't apply. */
        data class Repository(val url: String, val request: GitSettingsRequest, override val copiedFrom: Int? = null) : Create
    }
}

/** [StackProgress.stackId] of a create Portainer hasn't answered yet (or refused). */
const val STACK_NOT_CREATED = 0

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
    is StackOp.Create.Repository -> copy(request = request.copy(auth = request.auth?.copy(password = null)))
    else -> this
}

/** Passwords [op] sends, to blank out of any error text. */
private fun StackOp.secrets(): List<String> = when (this) {
    is StackOp.GitSettings -> listOfNotNull(request.auth?.password, (request.source?.auth as? GitSourceAuth.Basic)?.auth?.password)
    is StackOp.GitRedeploy -> listOfNotNull(request.auth?.password)
    is StackOp.Create.Repository -> listOfNotNull(request.auth?.password)
    else -> emptyList()
}.filter { it.isNotEmpty() }

/**
 * [running] until the operation and its check end; then [error] is null only when Portainer confirmed the result.
 * [stackId] is the stack it ran on, so a later stack with the same name never shows it; for a create it is
 * [STACK_NOT_CREATED] until Portainer returns the new stack.
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
     * running or [refusal] gives a reason; a finished result is replaced. A [StackOp.Create] goes through [create].
     */
    fun start(key: StackKey, stackId: Int, op: StackOp): Boolean = when (op) {
        is StackOp.Create -> create(key, op)
        // A migrate also deploys on the target: it must be free of other work too.
        is StackOp.Migrate ->
            refusal(key.serverId, op.targetEndpointId) == null && !isRunning(key.serverId, op.targetEndpointId) &&
                !isRunning(key.serverId, key.endpointId) && launch(key, stackId, op)
        else -> launch(key, stackId, op)
    }

    /**
     * Creates [key]'s stack with [op]. Refused (false) while any stack operation runs on [key]'s endpoint or
     * [refusal] gives a reason.
     */
    fun create(key: StackKey, op: StackOp.Create): Boolean {
        if (isRunning(key.serverId, key.endpointId)) return false
        return launch(key, STACK_NOT_CREATED, op)
    }

    /**
     * The create on [serverId]'s endpoint [endpointId] a create screen should show: a running one, else a failed one
     * nobody has cleared yet (a create that went well is shown by the new stack itself). Null when there is none.
     */
    fun pendingCreate(serverId: String, endpointId: Int): StackKey? {
        val creates = sessions.filter { (key, state) ->
            key.serverId == serverId && key.endpointId == endpointId && state.value?.op is StackOp.Create
        }
        return creates.entries.firstOrNull { it.value.value?.running == true }?.key
            ?: creates.entries.firstOrNull { it.value.value?.error != null }?.key
    }

    private fun launch(key: StackKey, stackId: Int, op: StackOp): Boolean {
        val state = flowFor(key)
        if (state.value?.running == true || refusal(key.serverId, key.endpointId) != null) return false
        // Progress outlives the request: it never holds a git password.
        val shown = op.withoutSecrets()
        var id = stackId
        state.value = StackProgress(shown, running = true, error = null, stackId = id)
        scope.launch {
            var client: PortainerClient? = null
            try {
                val c = clientFactory(key.serverId).also { client = it }
                run(c, key, id, op) { created ->
                    id = created
                    state.value = StackProgress(shown, running = true, error = null, stackId = created)
                }
                state.value = StackProgress(shown, running = false, error = null, stackId = id)
            } catch (e: CancellationException) {
                state.value = StackProgress(shown, running = false, error = "The stack operation was interrupted", stackId = id)
                throw e
            } catch (e: Exception) {
                val message = op.secrets().fold(safeMessage(e, "Unexpected error")) { m, secret -> m.replace(secret, "***") }
                state.value = StackProgress(shown, running = false, error = message, stackId = id)
            } finally {
                client?.close()
            }
        }
        return true
    }

    /** Why no stack operation may start on [serverId]'s endpoint [endpointId] now, or null. */
    fun refusal(serverId: String, endpointId: Int): String? = otherWork(serverId, endpointId)

    /** True while a stack operation on [serverId]'s endpoint [endpointId] has not finished yet (a migrate also occupies its target). */
    fun isRunning(serverId: String, endpointId: Int): Boolean = sessions.any { (key, state) ->
        val progress = state.value
        key.serverId == serverId && progress?.running == true &&
            (key.endpointId == endpointId || (progress.op as? StackOp.Migrate)?.targetEndpointId == endpointId)
    }

    /** The create on [serverId] that copies the stack [stackId] (running, or finished and not yet cleared), if any. */
    fun copyOf(serverId: String, stackId: Int): StackKey? = sessions.entries.firstOrNull { (key, state) ->
        key.serverId == serverId && (state.value?.op as? StackOp.Create)?.copiedFrom == stackId
    }?.key

    private val announcements = mutableMapOf<Pair<String, Int>, String>()

    /** Leaves a one-time message for the stack list of [serverId]'s endpoint [endpointId] (e.g. a stack that moved away). */
    fun announce(serverId: String, endpointId: Int, message: String) {
        announcements[serverId to endpointId] = message
    }

    /** The message left for that stack list, once. */
    fun takeAnnouncement(serverId: String, endpointId: Int): String? = announcements.remove(serverId to endpointId)

    /** Forgets a finished result (never a running one). */
    fun clear(key: StackKey) {
        val state = sessions[key] ?: return
        if (state.value?.running == false) state.value = null
    }

    private fun flowFor(key: StackKey) = sessions.getOrPut(key) { MutableStateFlow(null) }

    private suspend fun run(client: PortainerClient, key: StackKey, id: Int, op: StackOp, onCreated: (Int) -> Unit) {
        val endpointId = key.endpointId
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
            is StackOp.Migrate -> migrate(client, key, id, op)
            is StackOp.Create -> {
                val created = when (op) {
                    is StackOp.Create.Text -> client.createStackFromText(endpointId, key.stackName, op.file, op.env, fromAppTemplate = op.fromTemplate)
                    is StackOp.Create.Repository -> client.createStackFromRepository(endpointId, key.stackName, op.url, op.request)
                }
                val newId = created.id?.takeIf { it != STACK_NOT_CREATED }
                    ?: throw IllegalStateException("Portainer didn't return the new stack.")
                onCreated(newId)
                // From CE 2.44 the deploy continues after the response.
                awaitStatus(client, newId, StackStatus.Active, "Portainer reports the stack as stopped after creating it.")
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

    /**
     * CE 2.45 migrates synchronously with no rollback: it deploys on the target, removes the stack's resources from the
     * source, then moves the record and answers with the stack (same id). So a 200 naming the target is the result. A
     * request refused up front (400/401/403/404/409) changed nothing; any other failure may have stopped half way, so
     * the stack and the target are read to say what state it is in.
     */
    private suspend fun migrate(client: PortainerClient, key: StackKey, id: Int, op: StackOp.Migrate) {
        val name = op.newName?.trim()?.takeIf { it.isNotEmpty() } ?: key.stackName
        val moved = try {
            client.migrateStack(id, key.endpointId, op.targetEndpointId, op.newName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PortainerException.Unauthorized) {
            throw e
        } catch (e: PortainerException.NotFound) {
            throw e
        } catch (e: PortainerException.Api) {
            if (e.status in REFUSED_BEFORE_DEPLOY) throw e
            recoverMigrate(client, key, id, op, name, e)
            return
        } catch (e: Exception) {
            recoverMigrate(client, key, id, op, name, e)
            return
        }
        if (moved.endpointId == op.targetEndpointId && moved.name == name) return
        // An answer that doesn't show the move: read the stack itself before saying anything.
        if (readStack(client, id)?.let { it.endpointId == op.targetEndpointId && it.name == name } == true) return
        throw IllegalStateException("Portainer answered, but the stack is not on ${op.target()} under the name $name. Check both environments.")
    }

    private fun StackOp.Migrate.target() = targetName ?: "environment $targetEndpointId"

    private suspend fun readStack(client: PortainerClient, id: Int): Stack? = try {
        client.stack(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The request failed after it may have started: return when the stack turns out to be on the target, else throw what is known. */
    private suspend fun recoverMigrate(client: PortainerClient, key: StackKey, id: Int, op: StackOp.Migrate, name: String, cause: Exception) {
        val target = op.target()
        val source = op.sourceName ?: "environment ${key.endpointId}"
        val unknown = IllegalStateException(
            "${safeMessage(cause)} The outcome is unknown: check both $source and $target before retrying.",
        )
        val now = readStack(client, id)
        if (now != null && now.endpointId == op.targetEndpointId && now.name == name) return
        val external = try {
            client.stacks(op.targetEndpointId).any { it.control == StackControl.Limited && it.name == name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw unknown
        }
        when {
            external -> throw IllegalStateException(
                "The stack is now running on $target but isn't managed by Portainer there, and its containers on $source may " +
                    "be partly removed. Check both environments before retrying.",
            )
            // Connection lost or the stack unreadable: the server may still be working, so nothing can be ruled out.
            cause is PortainerException.Unreachable || now == null -> throw unknown
            else -> throw IllegalStateException("${safeMessage(cause)} $target may have leftover containers named $name.")
        }
    }
}

/** Migrate answers that mean Portainer refused before it deployed anything. */
private val REFUSED_BEFORE_DEPLOY = setOf(400, 401, 403, 404, 409)
