package dev.silas.portainer.app.editor

import dev.silas.portainer.app.recreate.StepStatus
import dev.silas.portainer.app.support.safeMessage
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.connectNetwork
import dev.silas.portainer.core.api.createContainer
import dev.silas.portainer.core.api.pullImage
import dev.silas.portainer.core.api.startContainer
import dev.silas.portainer.core.model.ImageRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Where a deploy was started from: the original container's id for a copy, [NEW_SOURCE] for Create. */
const val NEW_SOURCE = "new"

/**
 * A deploy (Deploy as copy, Create), keyed by where it was started from ([source]) and the container it creates
 * ([id]: its name, or a generated id for an unnamed new container), so the editor for that source can show it again.
 */
data class DeployKey(val serverId: String, val endpointId: Int, val source: String, val id: String)

enum class DeployStep { Pull, Create, ConnectNetworks, Start }

data class DeployStepState(val step: DeployStep, val status: StepStatus)

/** What to create: [body] for `POST /containers/create?name=<name>`, [extraNetworks] connected after create. */
data class DeployRequest(
    val name: String,
    val body: JsonObject,
    val extraNetworks: Map<String, JsonObject>,
    val image: ImageRef,
    val pull: Boolean,
    val registryId: Int?,
    val start: Boolean,
)

sealed interface DeployResult {
    data class Success(val id: String) : DeployResult

    /** [createdId] is set when the container was created before [step] failed: it is left in place. */
    data class Failure(val step: DeployStep?, val message: String, val createdId: String?) : DeployResult
}

/** [pullStatus] is the latest line of the image pull's progress. */
data class DeployProgress(
    val name: String,
    val steps: List<DeployStepState>,
    val result: DeployResult? = null,
    val pullStatus: String? = null,
)

internal fun deployStepLabel(step: DeployStep): String = when (step) {
    DeployStep.Pull -> "Pull image"
    DeployStep.Create -> "Create container"
    DeployStep.ConnectNetworks -> "Connect networks"
    DeployStep.Start -> "Start container"
}

/** What the failure screen says: the step's message, and whether a container was left behind. */
fun deployFailureText(progress: DeployProgress): String {
    val failure = progress.result as? DeployResult.Failure ?: return ""
    val container = if (progress.name.isEmpty()) "The new container" else "The container ${progress.name}"
    return if (failure.createdId != null) {
        "${failure.message}\n\n$container was created and has been left as it is; open it to fix or remove it."
    } else {
        "${failure.message}\n\nNothing was created or changed."
    }
}

/**
 * Copies and new containers being deployed: Pull → Create → ConnectNetworks → Start, collected in [scope] (an
 * application-level scope) so leaving the editor never stops one. Nothing here touches any container but the
 * one it creates, and nothing is ever removed: a failure after Create leaves the created container.
 * Call from one thread (the UI thread); the progress flows are safe to read anywhere.
 */
class DeploySessions(private val scope: CoroutineScope) {
    private val sessions = mutableMapOf<DeployKey, MutableStateFlow<DeployProgress?>>()

    private var unnamed = 0

    fun progress(key: DeployKey): StateFlow<DeployProgress?> = flowFor(key)

    /** A key for a new container without a name (Docker picks one); unique for the app's lifetime. */
    fun unnamedId(): String = "unnamed-${++unnamed}"

    /** A deploy started from [source] that is running or whose result wasn't acknowledged yet; running ones first. */
    fun find(serverId: String, endpointId: Int, source: String): DeployKey? {
        val mine = sessions.filter { (k, v) -> k.serverId == serverId && k.endpointId == endpointId && k.source == source && v.value != null }
        return (mine.entries.firstOrNull { it.value.value?.result == null } ?: mine.entries.firstOrNull())?.key
    }

    /**
     * Runs [request] through [client], which this session owns and closes when done. Refused (false), closing
     * [client], while a deploy for [key], or of a container with the same name on that endpoint, is still running;
     * a finished result is replaced.
     */
    fun start(key: DeployKey, client: PortainerClient, request: DeployRequest): Boolean {
        val state = flowFor(key)
        val sameName = request.name.isNotEmpty() && sessions.any { (k, v) ->
            k.serverId == key.serverId && k.endpointId == key.endpointId && v.value.let { it != null && it.result == null && it.name == request.name }
        }
        if (sameName || state.value.let { it != null && it.result == null }) {
            client.close()
            return false
        }
        val steps = DeployStep.entries.filter {
            when (it) {
                DeployStep.Pull -> request.pull
                DeployStep.ConnectNetworks -> request.extraNetworks.isNotEmpty()
                DeployStep.Start -> request.start
                DeployStep.Create -> true
            }
        }
        state.value = DeployProgress(request.name, steps.map { DeployStepState(it, StepStatus.Pending) })
        scope.launch {
            var current: DeployStep? = null
            var createdId: String? = null
            suspend fun <T> step(step: DeployStep, block: suspend () -> T): T {
                current = step
                state.update { it?.with(step, StepStatus.Active) }
                return block().also { state.update { p -> p?.with(step, StepStatus.Done) } }
            }
            try {
                val endpointId = key.endpointId
                if (request.pull) {
                    step(DeployStep.Pull) {
                        client.pullImage(endpointId, request.image, request.registryId) { line -> state.update { it?.copy(pullStatus = line) } }
                    }
                }
                val id = step(DeployStep.Create) { client.createContainer(endpointId, request.name, request.body) }
                createdId = id
                if (request.extraNetworks.isNotEmpty()) {
                    step(DeployStep.ConnectNetworks) {
                        request.extraNetworks.forEach { (network, config) -> client.connectNetwork(endpointId, network, id, config) }
                    }
                }
                if (request.start) step(DeployStep.Start) { client.startContainer(endpointId, id) }
                state.update { it?.copy(result = DeployResult.Success(id)) }
            } catch (e: CancellationException) {
                state.update { it?.fail(current, "The deploy was interrupted", createdId) }
                throw e
            } catch (e: Exception) {
                state.update { it?.fail(current, safeMessage(e, "Unexpected error"), createdId) }
            } finally {
                client.close()
            }
        }
        return true
    }

    /** Forgets a finished result (never a running one). */
    fun clear(key: DeployKey) {
        val state = sessions[key] ?: return
        if (state.value?.result != null) state.value = null
    }

    private fun flowFor(key: DeployKey) = sessions.getOrPut(key) { MutableStateFlow(null) }
}

private fun DeployProgress.with(step: DeployStep, status: StepStatus) =
    copy(steps = steps.map { if (it.step == step) it.copy(status = status) else it })

private fun DeployProgress.fail(step: DeployStep?, message: String, createdId: String?) = copy(
    steps = if (step == null) steps else steps.map { if (it.step == step && it.status == StepStatus.Active) it.copy(status = StepStatus.Failed) else it },
    result = DeployResult.Failure(step, message, createdId),
)
