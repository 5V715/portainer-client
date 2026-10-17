package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.COMPOSE_PROJECT_LABEL
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Health
import dev.silas.portainer.core.logs.LogOptions
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val NOT_MODIFIED = 304
private const val STOP_TIMEOUT_MS = 5 * 60_000L

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

/**
 * No `t` parameter, so Docker waits the container's own stop timeout; the request timeout is raised to
 * [STOP_TIMEOUT_MS] because that grace period is often longer than the client default.
 */
suspend fun PortainerClient.stopContainer(endpointId: Int, id: String) =
    containerAction(endpointId, id, "stop") {
        timeout {
            requestTimeoutMillis = STOP_TIMEOUT_MS
            socketTimeoutMillis = STOP_TIMEOUT_MS
        }
    }

suspend fun PortainerClient.restartContainer(endpointId: Int, id: String) =
    containerAction(endpointId, id, "restart")

suspend fun PortainerClient.removeContainer(endpointId: Int, id: String, force: Boolean = false) {
    execute(dockerPath(endpointId, "containers/$id")) {
        method = HttpMethod.Delete
        if (force) parameter("force", "true")
    }
}

/** Raw `GET /containers/{id}/json` document. */
suspend fun PortainerClient.inspectContainer(endpointId: Int, id: String): JsonObject =
    getJson(dockerPath(endpointId, "containers/$id/json"))

suspend fun PortainerClient.renameContainer(endpointId: Int, id: String, newName: String) {
    execute(dockerPath(endpointId, "containers/$id/rename")) {
        method = HttpMethod.Post
        parameter("name", newName)
    }
}

/** Creates (does not start) a container named [name] from [body]; returns the new container's `Id`. */
suspend fun PortainerClient.createContainer(endpointId: Int, name: String, body: JsonObject): String {
    val response = execute(dockerPath(endpointId, "containers/create")) {
        method = HttpMethod.Post
        parameter("name", name)
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    val created = decodeBody(response) { response.body<JsonObject>() }
    return (created["Id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw PortainerException.Api(response.status.value, "Unexpected response from Portainer")
}

/** Connects [containerId] to [network]. On a created-but-not-started container this only records the config. */
suspend fun PortainerClient.connectNetwork(endpointId: Int, network: String, containerId: String, endpointConfig: JsonObject) {
    execute(dockerPath(endpointId, "networks/$network/connect")) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        setBody(JsonObject(mapOf("Container" to JsonPrimitive(containerId), "EndpointConfig" to endpointConfig)))
    }
}

/** Docker answers 304 when the container is already in the requested state; that counts as success. */
private suspend fun PortainerClient.containerAction(
    endpointId: Int,
    id: String,
    action: String,
    configure: HttpRequestBuilder.() -> Unit = {},
) {
    execute(dockerPath(endpointId, "containers/$id/$action"), okStatuses = setOf(NOT_MODIFIED)) {
        method = HttpMethod.Post
        configure()
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
