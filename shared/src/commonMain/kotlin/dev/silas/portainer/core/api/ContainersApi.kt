package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Health
import dev.silas.portainer.core.logs.LogOptions
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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

private const val LOG_CHUNK_SIZE = 8 * 1024

/**
 * Streams raw (possibly multiplexed) log bytes; feed them to `LogDecoder`. When `follow` is set the
 * request has no timeout, otherwise the client default applies.
 */
fun PortainerClient.containerLogs(endpointId: Int, id: String, options: LogOptions): Flow<ByteArray> = channelFlow {
    try {
        prepareStream(dockerPath(endpointId, "containers/$id/logs")) {
            parameter("stdout", 1)
            parameter("stderr", 1)
            parameter("timestamps", 1)
            parameter("tail", options.tail?.toString() ?: "all")
            parameter("follow", options.follow)
            options.since?.let { parameter("since", it) }
            if (options.follow) {
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }
        }.execute { response ->
            throwForStatus(response)
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(LOG_CHUNK_SIZE)
            while (true) {
                val n = channel.readAvailable(buffer)
                if (n < 0) break
                if (n > 0) send(buffer.copyOf(n))
            }
        }
    } catch (e: Throwable) {
        throw mapTransportFailure(e)
    }
}
