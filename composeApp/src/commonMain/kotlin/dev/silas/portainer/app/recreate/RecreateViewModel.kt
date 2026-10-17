package dev.silas.portainer.app.recreate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.api.inspectImage
import dev.silas.portainer.core.api.registries
import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.recreate.PortainerDockerOps
import dev.silas.portainer.core.recreate.RecreateEdits
import dev.silas.portainer.core.recreate.RecreatePlan
import dev.silas.portainer.core.recreate.RecreatePlanner
import dev.silas.portainer.core.recreate.Recreator
import dev.silas.portainer.core.recreate.isProtectedImage
import dev.silas.portainer.core.registry.matchRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where the flow is. Running and Done mirror the app-level [RecreateSessions] entry, not ViewModel state. */
sealed interface RecreatePhase {
    data object Loading : RecreatePhase

    /** Loading failed, or the container runs Portainer itself (no form, no review). */
    data class Error(val message: String) : RecreatePhase
    data object Editing : RecreatePhase
    data class Reviewing(val plan: RecreatePlan) : RecreatePhase
    data class Running(val steps: List<StepState>) : RecreatePhase
    data class Done(val steps: List<StepState>, val result: RecreateResult) : RecreatePhase
}

data class RecreateState(
    val phase: RecreatePhase = RecreatePhase.Loading,
    val containerName: String = "",
    val image: String = "",
    /** Complete desired `Env`, as `K=V` entries (an entry without `=` is kept as-is). */
    val env: List<String> = emptyList(),
    val pull: Boolean = true,
    val registries: List<Registry> = emptyList(),
    val registryId: Int? = null,
    /** Validation message for the Editing form; cleared on the next edit. */
    val validationError: String? = null,
)

class RecreateViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val containerId: String,
) : ViewModel() {
    private val key = SessionKey(serverId, endpointId, containerId)
    private val sessions = container.recreateSessions
    private val _state = MutableStateFlow(RecreateState())
    val state: StateFlow<RecreateState> = _state

    private var inspect: JsonObject? = null
    private var oldImageConfig: JsonObject? = null
    private var registryPicked = false

    init {
        // A recreate may already be running or finished (screen left and re-entered, rotation): show it as-is.
        // The old container may no longer exist, so there is nothing to load in that case.
        val existing = sessions.progress(key)
        viewModelScope.launch { existing.collect { progress -> if (progress != null) showProgress(progress) } }
        if (existing.value == null) viewModelScope.launch { load() }
    }

    private fun showProgress(p: RecreateProgress) {
        val phase = p.result?.let { RecreatePhase.Done(p.steps, it) } ?: RecreatePhase.Running(p.steps)
        _state.update { it.copy(phase = phase) }
    }

    private suspend fun load() {
        val message = try {
            val client = container.client(serverId)
            val inspected = client.inspectContainer(endpointId, containerId)
            val config = inspected["Config"] as? JsonObject
            val image = (config?.get("Image") as? JsonPrimitive)?.content.orEmpty()
            if (isProtectedImage(image)) {
                PROTECTED_IMAGE_MESSAGE
            } else {
                val imageId = (inspected["Image"] as? JsonPrimitive)?.content
                oldImageConfig = imageId?.let { client.inspectImage(endpointId, it) }
                // Registries are only a convenience; an account that can't list them still gets to edit.
                val registries = try {
                    client.registries()
                } catch (e: PortainerException) {
                    emptyList()
                }
                inspect = inspected
                val env = (config?.get("Env") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                _state.update {
                    // A recreate may have been started elsewhere while loading; the progress collector owns the phase then.
                    val phase = if (it.phase == RecreatePhase.Loading) RecreatePhase.Editing else it.phase
                    it.copy(
                        phase = phase,
                        containerName = (inspected["Name"] as? JsonPrimitive)?.content.orEmpty().removePrefix("/"),
                        image = image,
                        env = env,
                        registries = registries,
                        registryId = matchFor(image, registries),
                    )
                }
                null
            }
        } catch (e: CancellationException) {
            // A client closed by invalidation cancels the call; only rethrow if this coroutine itself was cancelled.
            if (!currentCoroutineContext().isActive) throw e
            "The connection was interrupted"
        } catch (e: PortainerException) {
            e.message ?: "Request failed"
        } catch (e: IllegalStateException) {
            e.message ?: "Request failed"
        }
        if (message != null) _state.update { it.copy(phase = RecreatePhase.Error(message)) }
    }

    private fun matchFor(image: String, registries: List<Registry>): Int? =
        if (image.isBlank()) null else matchRegistry(ImageRef.parse(image.trim()), registries)?.id

    private fun edit(block: (RecreateState) -> RecreateState) = _state.update {
        if (it.phase == RecreatePhase.Editing) block(it).copy(validationError = null) else it
    }

    fun setImage(image: String) = edit {
        it.copy(image = image, registryId = if (registryPicked) it.registryId else matchFor(image, it.registries))
    }

    /** Replaces the whole env list (`K=V` entries). */
    fun setEnv(env: List<String>) = edit { it.copy(env = env) }

    /** Edits row [index]; an entry that had no `=` and still has no value stays without one. */
    fun setEnvRow(index: Int, key: String, value: String) = edit { s ->
        val old = s.env.getOrNull(index) ?: return@edit s
        val entry = if (value.isEmpty() && '=' !in old) key else "$key=$value"
        s.copy(env = s.env.toMutableList().also { it[index] = entry })
    }

    fun addEnvRow() = edit { it.copy(env = it.env + "=") }

    fun removeEnvRow(index: Int) = edit { s -> s.copy(env = s.env.filterIndexed { i, _ -> i != index }) }

    fun setPull(pull: Boolean) = edit { it.copy(pull = pull) }

    /** Picks a registry by id, or none (public) with null. Stops the automatic match for this screen. */
    fun setRegistry(id: Int?) {
        registryPicked = true
        edit { it.copy(registryId = id) }
    }

    /** Validates the form and builds the plan. Unavailable (no-op) unless Editing, so a protected image never gets here. */
    fun review() {
        val current = _state.value
        val inspected = inspect
        if (current.phase != RecreatePhase.Editing || inspected == null) return
        val image = current.image.trim()
        val env = current.env.filter { envKey(it).isNotBlank() }
        val problem = when {
            image.isEmpty() -> "Image is required"
            isProtectedImage(image) -> PROTECTED_IMAGE_MESSAGE
            else -> env.groupBy(::envKey).entries.firstOrNull { it.value.size > 1 }?.let { "Duplicate variable: ${it.key}" }
        }
        if (problem != null) {
            _state.update { it.copy(validationError = problem) }
            return
        }
        val plan = RecreatePlanner.plan(inspected, oldImageConfig, RecreateEdits(image, env, current.pull, current.registryId))
        _state.update { it.copy(phase = RecreatePhase.Reviewing(plan), validationError = null) }
    }

    /** "Cancel" on the review: back to the form with the edits kept. */
    fun cancelReview() = _state.update { if (it.phase is RecreatePhase.Reviewing) it.copy(phase = RecreatePhase.Editing) else it }

    /** Runs the reviewed plan in the app scope; this ViewModel being cleared does not stop it. */
    fun confirm() {
        val phase = _state.value.phase as? RecreatePhase.Reviewing ?: return
        viewModelScope.launch {
            val client = try {
                container.client(serverId)
            } catch (e: IllegalStateException) {
                _state.update { it.copy(phase = RecreatePhase.Error(e.message ?: "Request failed")) }
                return@launch
            }
            val recreator = Recreator(PortainerDockerOps(client, endpointId), container.nowEpochSeconds)
            sessions.start(key, recreator, containerId, phase.plan)
        }
    }

    /** Forgets a finished result once the screen has acted on it (navigated away). */
    fun acknowledge() = sessions.clear(key)
}

/** The variable name of a `K=V` entry (the whole entry when it has no `=`). */
private fun envKey(entry: String): String = entry.substringBefore('=').trim()
