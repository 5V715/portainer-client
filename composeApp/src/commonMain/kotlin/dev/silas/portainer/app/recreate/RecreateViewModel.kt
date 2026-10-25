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
import dev.silas.portainer.core.recreate.RecreateEdits
import dev.silas.portainer.core.recreate.RecreatePlan
import dev.silas.portainer.core.recreate.RecreatePlanner
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

const val CONTAINER_CHANGED_NOTICE =
    "The container changed since you opened this screen — the summary reflects its current settings."

/** Where the flow is. Running and Done mirror the app-level [RecreateSessions] entry, not ViewModel state. */
sealed interface RecreatePhase {
    data object Loading : RecreatePhase

    /** Loading failed, or the container runs Portainer itself (no form, no review). */
    data class Error(val message: String) : RecreatePhase
    data object Editing : RecreatePhase
    /** [notice] is set when the container changed since the form was loaded (the plan reflects its current settings). */
    data class Reviewing(val plan: RecreatePlan, val notice: String? = null) : RecreatePhase
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
    /** Review was requested and the container is being re-inspected; the form is read-only meanwhile. */
    val checking: Boolean = false,
)

/** What the form was loaded with, to tell whether the container changed before the review. */
private data class ContainerSnapshot(val image: String, val env: List<String>, val running: Boolean)

private fun JsonObject.snapshot(): ContainerSnapshot {
    val config = this["Config"] as? JsonObject
    return ContainerSnapshot(
        image = (config?.get("Image") as? JsonPrimitive)?.content.orEmpty(),
        env = (config?.get("Env") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
        running = ((this["State"] as? JsonObject)?.get("Running") as? JsonPrimitive)?.content == "true",
    )
}

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

    /** Set once the form is loaded; review() compares a fresh inspect against it. */
    private var loaded: ContainerSnapshot? = null
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
        _state.update { it.copy(phase = phase, containerName = it.containerName.ifEmpty { p.containerName }) }
    }

    private suspend fun load() {
        val message = try {
            val client = container.client(serverId)
            val inspected = client.inspectContainer(endpointId, containerId)
            val snapshot = inspected.snapshot()
            val image = snapshot.image
            if (isProtectedImage(image)) {
                PROTECTED_IMAGE_MESSAGE
            } else {
                // Registries are only a convenience; an account that can't list them still gets to edit.
                val registries = try {
                    client.registries()
                } catch (e: PortainerException) {
                    emptyList()
                }
                loaded = snapshot
                _state.update {
                    // A recreate may have been started elsewhere while loading; the progress collector owns the phase then.
                    val phase = if (it.phase == RecreatePhase.Loading) RecreatePhase.Editing else it.phase
                    it.copy(
                        phase = phase,
                        containerName = (inspected["Name"] as? JsonPrimitive)?.content.orEmpty().removePrefix("/"),
                        image = image,
                        env = snapshot.env,
                        registries = registries,
                        registryId = matchFor(image, registries),
                    )
                }
                null
            }
        } catch (e: Exception) {
            requestFailure(e)
        }
        if (message != null) _state.update { it.copy(phase = RecreatePhase.Error(message)) }
    }

    /** The message for a failed request; rethrows when this coroutine itself was cancelled. */
    private suspend fun requestFailure(e: Exception): String = when (e) {
        // A client closed by invalidation cancels the call; only rethrow if this coroutine itself was cancelled.
        is CancellationException -> if (currentCoroutineContext().isActive) "The connection was interrupted" else throw e
        is PortainerException, is IllegalStateException -> e.message ?: "Request failed"
        else -> throw e
    }

    private fun matchFor(image: String, registries: List<Registry>): Int? =
        if (image.isBlank()) null else matchRegistry(ImageRef.parse(image.trim()), registries)?.id

    private fun edit(block: (RecreateState) -> RecreateState) = _state.update {
        if (it.phase == RecreatePhase.Editing && !it.checking) block(it).copy(validationError = null) else it
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

    /**
     * Validates the form, then re-inspects the container and its current image so the plan is built from what
     * the container is now (it may have changed since the screen opened), plus the user's edits.
     * Unavailable (no-op) unless Editing, so a protected image never gets here.
     */
    fun review() {
        val current = _state.value
        val loadedWith = loaded
        if (current.phase != RecreatePhase.Editing || current.checking || loadedWith == null) return
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
        val edits = RecreateEdits(image, env, current.pull, current.registryId)
        _state.update { it.copy(checking = true, validationError = null) }
        viewModelScope.launch {
            var error: String? = null
            val reviewing = try {
                val client = container.client(serverId)
                val fresh = client.inspectContainer(endpointId, containerId)
                val now = fresh.snapshot()
                if (isProtectedImage(now.image)) {
                    error = PROTECTED_IMAGE_MESSAGE
                    null
                } else {
                    val imageConfig = (fresh["Image"] as? JsonPrimitive)?.content?.let { client.inspectImage(endpointId, it) }
                    val notice = if (now != loadedWith) CONTAINER_CHANGED_NOTICE else null
                    RecreatePhase.Reviewing(RecreatePlanner.plan(fresh, imageConfig, edits), notice)
                }
            } catch (e: Exception) {
                error = requestFailure(e)
                null
            }
            _state.update {
                // A recreate started elsewhere meanwhile owns the phase; only leave the form if still on it.
                when {
                    it.phase != RecreatePhase.Editing -> it.copy(checking = false)
                    reviewing != null -> it.copy(phase = reviewing, checking = false)
                    else -> it.copy(validationError = error, checking = false)
                }
            }
        }
    }

    /** "Cancel" on the review: back to the form with the edits kept. */
    fun cancelReview() = _state.update { if (it.phase is RecreatePhase.Reviewing) it.copy(phase = RecreatePhase.Editing) else it }

    /** Runs the reviewed plan in the app scope; this ViewModel being cleared does not stop it. */
    fun confirm() {
        val phase = _state.value.phase as? RecreatePhase.Reviewing ?: return
        viewModelScope.launch {
            // A client of its own, owned by the session: saving or deleting the server can't close it mid-recreate.
            val client = try {
                container.dedicatedClient(serverId)
            } catch (e: IllegalStateException) {
                _state.update { it.copy(phase = RecreatePhase.Error(e.message ?: "Request failed")) }
                return@launch
            }
            sessions.start(key, client, containerId, phase.plan)
        }
    }

    /** Forgets a finished result once the screen has acted on it (navigated away). */
    fun acknowledge() = sessions.clear(key)
}

/** The variable name of a `K=V` entry (the whole entry when it has no `=`). */
private fun envKey(entry: String): String = entry.substringBefore('=').trim()
