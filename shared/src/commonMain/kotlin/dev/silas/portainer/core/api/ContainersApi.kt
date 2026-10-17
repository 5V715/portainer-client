package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Health
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val COMPOSE_PROJECT_LABEL = "com.docker.compose.project"
private const val NOT_MODIFIED = 304

@Serializable
private data class ContainerDto(
    @SerialName("Id") val id: String,
    @SerialName("Names") val names: List<String> = emptyList(),
    @SerialName("Image") val image: String = "",
    @SerialName("State") val state: String? = null,
    @SerialName("Status") val status: String = "",
    @SerialName("Labels") val labels: Map<String, String>? = null,
)

internal fun parseHealth(status: String): Health? = when {
    status.contains("(unhealthy)") -> Health.Unhealthy
    status.contains("(healthy)") -> Health.Healthy
    status.contains("(health: starting)") -> Health.Starting
    else -> null
}

private fun ContainerDto.toSummary(): ContainerSummary {
    val labels = labels.orEmpty()
    return ContainerSummary(
        id = id,
        name = names.firstOrNull()?.removePrefix("/") ?: id.take(12),
        image = image,
        state = ContainerState.fromDocker(state),
        statusText = status,
        health = parseHealth(status),
        stack = labels[COMPOSE_PROJECT_LABEL],
        labels = labels,
    )
}

suspend fun PortainerClient.containers(endpointId: Int): List<ContainerSummary> =
    getJson<List<ContainerDto>>(dockerPath(endpointId, "containers/json?all=1")).map { it.toSummary() }

suspend fun PortainerClient.startContainer(endpointId: Int, id: String) =
    containerAction(endpointId, id, "start")

suspend fun PortainerClient.stopContainer(endpointId: Int, id: String) =
    containerAction(endpointId, id, "stop")

suspend fun PortainerClient.restartContainer(endpointId: Int, id: String) =
    containerAction(endpointId, id, "restart")

suspend fun PortainerClient.removeContainer(endpointId: Int, id: String, force: Boolean = false) {
    execute(dockerPath(endpointId, "containers/$id")) {
        method = HttpMethod.Delete
        if (force) parameter("force", "true")
    }
}

/** Docker answers 304 when the container is already in the requested state; that counts as success. */
private suspend fun PortainerClient.containerAction(endpointId: Int, id: String, action: String) {
    execute(dockerPath(endpointId, "containers/$id/$action"), okStatuses = setOf(NOT_MODIFIED)) {
        method = HttpMethod.Post
    }
}
