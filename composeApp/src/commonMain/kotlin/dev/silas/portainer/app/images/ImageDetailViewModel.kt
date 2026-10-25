package dev.silas.portainer.app.images

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.inspectImage
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.ImageSummary
import dev.silas.portainer.core.recreate.isProtectedImage
import dev.silas.portainer.core.resources.ResourceProtection
import dev.silas.portainer.core.resources.ResourceUsage
import dev.silas.portainer.core.resources.resolveIdOnlyImages
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ImageDetail(
    val id: String,
    val tags: List<String>,
    val size: Long,
    /** ISO timestamp as Docker reports it. */
    val created: String,
    val architecture: String,
    val os: String,
    /** Label/value rows from the image config (entrypoint, command, working dir, user, ports). */
    val config: List<Pair<String, String>>,
    val containers: List<ContainerSummary>,
    val protectedImage: Boolean,
)

data class ImageDetailState(
    val detail: ImageDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class ImageDetailViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val imageId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ImageDetailState())
    val state: StateFlow<ImageDetailState> = _state

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val client = container.client(serverId)
                val inspect = client.inspectImage(endpointId, imageId)
                if (inspect == null) {
                    _state.update { it.copy(detail = null, error = "Image not found") }
                } else {
                    val containers = client.containers(endpointId)
                    val protection = ResourceProtection(containers, client.resolveIdOnlyImages(endpointId, containers))
                    val using = ResourceUsage(containers).containersUsingImage(imageId)
                    val detail = parseDetail(inspect, using)
                    val summary = ImageSummary(id = detail.id.ifEmpty { imageId }, repoTags = detail.tags)
                    val protectedImage = protection.isImageProtected(summary) || using.any(protection::isImageUnresolved)
                    _state.update { it.copy(detail = detail.copy(protectedImage = protectedImage), error = null) }
                }
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { it.text() }.orEmpty()

internal fun parseDetail(inspect: JsonObject, using: List<ContainerSummary>): ImageDetail {
    val config = inspect["Config"] as? JsonObject
    val tags = inspect["RepoTags"].strings().filter { it != "<none>:<none>" }
    val rows = buildList {
        config?.get("Entrypoint").strings().takeIf { it.isNotEmpty() }?.let { add("Entrypoint" to it.joinToString(" ")) }
        config?.get("Cmd").strings().takeIf { it.isNotEmpty() }?.let { add("Command" to it.joinToString(" ")) }
        config?.get("WorkingDir").text()?.takeIf { it.isNotBlank() }?.let { add("Working dir" to it) }
        config?.get("User").text()?.takeIf { it.isNotBlank() }?.let { add("User" to it) }
        (config?.get("ExposedPorts") as? JsonObject)?.keys?.takeIf { it.isNotEmpty() }?.let { add("Exposed ports" to it.sorted().joinToString(", ")) }
    }
    return ImageDetail(
        id = inspect["Id"].text().orEmpty(),
        tags = tags,
        size = (inspect["Size"] as? JsonPrimitive)?.longOrNull ?: 0,
        created = inspect["Created"].text().orEmpty(),
        architecture = inspect["Architecture"].text().orEmpty(),
        os = inspect["Os"].text().orEmpty(),
        config = rows,
        containers = using,
        // Tag check only; the view model adds the containers that run Portainer.
        protectedImage = inspect["RepoTags"].strings().any(::isProtectedImage),
    )
}
