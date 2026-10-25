package dev.silas.portainer.app.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.app.recreate.SessionKey
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.api.inspectImage
import dev.silas.portainer.core.api.registries
import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.recreate.RecreateEdits
import dev.silas.portainer.core.recreate.RecreatePlan
import dev.silas.portainer.core.recreate.RecreatePlanner
import dev.silas.portainer.core.recreate.RecreateWarning
import dev.silas.portainer.core.recreate.isProtectedImage
import dev.silas.portainer.core.registry.matchRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class QuickPhase {
    /** No sheet. */
    Closed,
    /** Sheet open, inspecting the container. */
    Loading,
    /** Sheet open and ready to confirm. */
    Ready,
    /** The recreate session was started; the caller navigates to its progress and calls [QuickRecreateViewModel.dismiss]. */
    Started,
}

data class QuickRecreateState(
    val phase: QuickPhase = QuickPhase.Closed,
    val pull: Boolean = true,
    val registries: List<Registry> = emptyList(),
    val registryId: Int? = null,
    val warnings: List<RecreateWarning> = emptyList(),
    /** Shown instead of the sheet (protected container, failed request); cleared with [QuickRecreateViewModel.clearError]. */
    val error: String? = null,
    val starting: Boolean = false,
)

/** Recreates a container with exactly its current image and environment, optionally pulling the image first. */
class QuickRecreateViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val containerId: String,
) : ViewModel() {
    private val key = SessionKey(serverId, endpointId, containerId)
    private val _state = MutableStateFlow(QuickRecreateState())
    val state: StateFlow<QuickRecreateState> = _state

    /** Opens the sheet and loads the container; no-op while already open. */
    fun open() {
        if (_state.value.phase != QuickPhase.Closed) return
        _state.value = QuickRecreateState(phase = QuickPhase.Loading)
        viewModelScope.launch {
            try {
                val client = container.client(serverId)
                val fresh = client.inspectContainer(endpointId, containerId)
                val image = fresh.configImage()
                if (isProtectedImage(image)) return@launch fail(PROTECTED_IMAGE_MESSAGE)
                // Registries are only a convenience; an account that can't list them still gets to recreate.
                val registries = try {
                    client.registries()
                } catch (e: Exception) {
                    failureMessage(e) // rethrows only when this coroutine was cancelled
                    emptyList()
                }
                val match = matchFor(image, registries)
                val plan = plan(client, fresh, pull = true, registryId = match)
                _state.update {
                    if (it.phase != QuickPhase.Loading) it
                    else it.copy(phase = QuickPhase.Ready, registries = registries, registryId = match, warnings = plan.warnings)
                }
            } catch (e: Exception) {
                fail(failureMessage(e))
            }
        }
    }

    fun setPull(pull: Boolean) = _state.update { if (it.phase == QuickPhase.Ready && !it.starting) it.copy(pull = pull) else it }

    /** Picks a registry by id, or none (public) with null. */
    fun setRegistry(id: Int?) {
        _state.update { if (it.phase == QuickPhase.Ready && !it.starting) it.copy(registryId = id) else it }
    }

    /** Re-inspects the container, plans from its current state, and starts the recreate in the app scope. */
    fun confirm() {
        val current = _state.value
        if (current.phase != QuickPhase.Ready || current.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                val client = container.client(serverId)
                val fresh = client.inspectContainer(endpointId, containerId)
                if (isProtectedImage(fresh.configImage())) return@launch fail(PROTECTED_IMAGE_MESSAGE)
                val plan = plan(client, fresh, current.pull, current.registryId)
                if (plan.warnings != current.warnings) {
                    // The container changed since the sheet opened: show what applies now and ask again.
                    _state.update { it.copy(warnings = plan.warnings, starting = false) }
                    return@launch
                }
                val sessions = container.recreateSessions
                // A finished result of an earlier run would make start() ignore this one; a run in flight is just shown.
                if (sessions.progress(key).value?.result != null) sessions.clear(key)
                if (sessions.progress(key).value == null) {
                    // A client of its own, owned by the session: saving or deleting the server can't close it mid-recreate.
                    sessions.start(key, container.dedicatedClient(serverId), containerId, plan)
                }
                _state.update { if (it.phase == QuickPhase.Ready && it.starting) it.copy(phase = QuickPhase.Started, starting = false) else it }
            } catch (e: Exception) {
                fail(failureMessage(e))
            }
        }
    }

    /** Closes the sheet. Refused while the recreate is being started, so cancelling can't race it into running. */
    fun dismiss() {
        _state.update { if (it.phase == QuickPhase.Ready && it.starting) it else QuickRecreateState(error = it.error) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun fail(message: String) {
        _state.value = QuickRecreateState(error = message)
    }

    /** Edits equal the container's current image and env, so the plan changes nothing but the optional pull. */
    private suspend fun plan(client: PortainerClient, fresh: JsonObject, pull: Boolean, registryId: Int?): RecreatePlan {
        val config = fresh["Config"] as? JsonObject
        val env = (config?.get("Env") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val imageConfig = (fresh["Image"] as? JsonPrimitive)?.content?.let { client.inspectImage(endpointId, it) }
        return RecreatePlanner.plan(fresh, imageConfig, RecreateEdits(fresh.configImage(), env, pull, registryId))
    }

    private fun JsonObject.configImage(): String = ((this["Config"] as? JsonObject)?.get("Image") as? JsonPrimitive)?.content.orEmpty()

    private fun matchFor(image: String, registries: List<Registry>): Int? =
        if (image.isBlank()) null else matchRegistry(ImageRef.parse(image.trim()), registries)?.id
}
