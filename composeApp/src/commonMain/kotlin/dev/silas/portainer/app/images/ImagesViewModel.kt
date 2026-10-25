package dev.silas.portainer.app.images

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.images
import dev.silas.portainer.core.api.pullImage
import dev.silas.portainer.core.api.registries
import dev.silas.portainer.core.api.removeImage
import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.model.ImageSummary
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.model.isProtected
import dev.silas.portainer.core.registry.matchRegistry
import dev.silas.portainer.core.resources.ResourceUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val PROTECTED_IMAGE_REMOVE_MESSAGE = "This image runs Portainer and can't be removed from this app."

data class ImageItem(val image: ImageSummary, val used: Boolean) {
    val protected: Boolean get() = image.isProtected()
    val label: String get() = imageLabel(image)
}

/** First real tag, or "<untagged>" plus the short id. */
fun imageLabel(image: ImageSummary): String =
    image.repoTags.firstOrNull { it != "<none>:<none>" } ?: "<untagged> ${shortId(image.id)}"

fun shortId(id: String): String = id.removePrefix("sha256:").take(12)

/** Docker refused to remove an image that is in use; [message] is Docker's own text. */
data class ForcePrompt(val id: String, val label: String, val message: String)

sealed interface PullStatus {
    data object Idle : PullStatus
    data object Pulling : PullStatus
    data class Done(val text: String) : PullStatus
    data class Failed(val text: String) : PullStatus
}

data class PullState(
    val reference: String = "",
    val registries: List<Registry> = emptyList(),
    val registryId: Int? = null,
    val status: PullStatus = PullStatus.Idle,
)

data class ImagesState(
    val items: List<ImageItem> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val busyIds: Set<String> = emptySet(),
    val forcePrompt: ForcePrompt? = null,
    val pull: PullState = PullState(),
) {
    val visible: List<ImageItem>
        get() {
            val q = query.trim()
            return items.filter { q.isEmpty() || it.image.repoTags.any { t -> t.contains(q, ignoreCase = true) } || it.image.id.contains(q, ignoreCase = true) }
                .sortedBy { it.label.lowercase() }
        }
}

class ImagesViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(ImagesState())
    val state: StateFlow<ImagesState> = _state

    private var loadJob: Job? = null
    private var registryPicked = false

    init {
        refresh()
    }

    /** Starts a load, cancelling any load still in flight so an older response can't overwrite newer data. */
    fun refresh() {
        _state.update { it.copy(loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    private var resumedBefore = false

    /** Back from the detail screen: reload (the first resume is skipped, init already loads). */
    fun onScreenResumed() {
        if (resumedBefore) refresh() else resumedBefore = true
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun load() {
        guard {
            val client = container.client(serverId)
            val images = client.images(endpointId)
            val usage = ResourceUsage(client.containers(endpointId))
            val items = images.map { ImageItem(it, usage.isImageUsed(it.id)) }
            _state.update { it.copy(items = items) }
        }
        _state.update { it.copy(loading = false) }
    }

    /**
     * Removes [id] (never forced unless [force]). Protected images are refused without any API call. Docker's 409
     * (in use) becomes a [ForcePrompt] showing Docker's message, so the user can confirm a forced removal.
     */
    fun remove(id: String, force: Boolean) {
        val target = _state.value.items.firstOrNull { it.image.id == id } ?: return
        if (target.protected) {
            _state.update { it.copy(error = PROTECTED_IMAGE_REMOVE_MESSAGE, forcePrompt = null) }
            return
        }
        if (id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + id) }
        viewModelScope.launch {
            try {
                val conflict = try {
                    container.client(serverId).removeImage(endpointId, id, force)
                    null
                } catch (e: PortainerException.Api) {
                    if (e.status == 409 && !force) e.message ?: "The image is in use" else throw e
                }
                if (conflict != null) {
                    _state.update { it.copy(forcePrompt = ForcePrompt(id, target.label, conflict)) }
                } else {
                    refresh()
                }
            } catch (e: Exception) {
                failure(e)
            } finally {
                _state.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    /** Second confirmation: removes the image of the open [ForcePrompt] with force. */
    fun confirmForce() {
        val prompt = _state.value.forcePrompt ?: return
        _state.update { it.copy(forcePrompt = null) }
        remove(prompt.id, force = true)
    }

    fun dismissForce() = _state.update { it.copy(forcePrompt = null) }

    // ---- Pull ----

    /** Opens the pull sheet: resets it and loads registries (only a convenience; failure leaves the list empty). */
    fun openPull() {
        registryPicked = false
        if (_state.value.pull.status == PullStatus.Pulling) return
        _state.update { it.copy(pull = PullState(registries = it.pull.registries)) }
        viewModelScope.launch {
            val registries = try {
                container.client(serverId).registries()
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                emptyList()
            } catch (e: PortainerException) {
                emptyList()
            } catch (e: IllegalStateException) {
                emptyList()
            }
            _state.update { it.copy(pull = it.pull.copy(registries = registries, registryId = autoMatch(it.pull.reference, registries, it.pull.registryId))) }
        }
    }

    private fun matchFor(reference: String, registries: List<Registry>): Int? =
        if (reference.isBlank()) null else matchRegistry(ImageRef.parse(reference.trim()), registries)?.id

    private fun autoMatch(reference: String, registries: List<Registry>, current: Int?): Int? =
        if (registryPicked) current else matchFor(reference, registries)

    fun setPullReference(reference: String) = _state.update {
        it.copy(pull = it.pull.copy(reference = reference, registryId = autoMatch(reference, it.pull.registries, it.pull.registryId)))
    }

    /** Picks a registry by id, or none (public) with null; stops the automatic match. */
    fun setPullRegistry(id: Int?) {
        registryPicked = true
        _state.update { it.copy(pull = it.pull.copy(registryId = id)) }
    }

    fun pull() = pull(_state.value.pull.reference, _state.value.pull.registryId)

    /** Pulls [reference]; runs in [viewModelScope] (not destructive) and refreshes the list on success. */
    fun pull(reference: String, registryId: Int?) {
        val ref = reference.trim()
        if (ref.isEmpty() || _state.value.pull.status == PullStatus.Pulling) return
        _state.update { it.copy(pull = it.pull.copy(status = PullStatus.Pulling)) }
        viewModelScope.launch {
            val status = try {
                container.client(serverId).pullImage(endpointId, ImageRef.parse(ref), registryId)
                PullStatus.Done("Pulled $ref")
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                PullStatus.Failed("The connection was interrupted")
            } catch (e: PortainerException) {
                PullStatus.Failed(e.message ?: "Pull failed")
            } catch (e: IllegalStateException) {
                PullStatus.Failed(e.message ?: "Pull failed")
            }
            _state.update { it.copy(pull = it.pull.copy(status = status)) }
            if (status is PullStatus.Done) refresh()
        }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            failure(e)
        }
    }

    private suspend fun failure(e: Exception) {
        val message = when (e) {
            is CancellationException -> {
                // A client closed by invalidation cancels the call; only rethrow if this coroutine itself was cancelled.
                if (!currentCoroutineContext().isActive) throw e
                "The connection was interrupted"
            }
            is PortainerException, is IllegalStateException -> e.message ?: "Request failed"
            else -> throw e
        }
        _state.update { it.copy(error = message) }
    }
}
