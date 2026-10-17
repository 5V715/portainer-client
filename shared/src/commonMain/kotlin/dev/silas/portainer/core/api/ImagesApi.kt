package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.registry.registryAuthHeader
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val PULL_DENIED = "Image pull was denied. Check the registry credentials in Registries."
private val DENIAL_MARKERS = listOf(
    "pull access denied",
    "requested access to the resource is denied",
    "authentication required",
    "unauthorized: ",
    "no basic auth credentials",
)

private fun looksDenied(text: String): Boolean = DENIAL_MARKERS.any { text.contains(it, ignoreCase = true) }

/** Returns the image inspect document, or null when the image does not exist. */
suspend fun PortainerClient.inspectImage(endpointId: Int, imageId: String): JsonObject? = try {
    val response = execute(dockerPath(endpointId, "images/$imageId/json"))
    decodeBody(response) { response.body<JsonObject>() }
} catch (e: PortainerException.NotFound) {
    null
}

/**
 * Pulls [ref]. Docker reports pull failures (bad tag, registry auth) inside the HTTP 200 progress
 * stream, so the whole stream is read and any `error` line fails the call.
 *
 * Denial mapping: a stream error or any non-2xx body with a registry-style denial phrase becomes the
 * "pull was denied" message. A 401/403 without one (including Portainer RBAC "Access denied ...")
 * stays [PortainerException.Unauthorized], because it is ambiguous with a bad API key. A digest pins the pull.
 */
suspend fun PortainerClient.pullImage(endpointId: Int, ref: ImageRef, registryId: Int?) {
    try {
        prepareStream(dockerPath(endpointId, "images/create")) {
            method = HttpMethod.Post
            parameter("fromImage", ref.name)
            parameter("tag", ref.digest ?: ref.tag ?: "latest")
            if (registryId != null) header("X-Registry-Auth", registryAuthHeader(registryId))
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }.execute { response ->
            throwForPullStatus(response)
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                streamError(line)?.let { throw pullFailure(200, it) }
            }
        }
    } catch (e: Throwable) {
        throw mapTransportFailure(e)
    }
}

private fun pullFailure(status: Int, text: String) =
    PortainerException.Api(status, if (looksDenied(text)) PULL_DENIED else text)

private suspend fun PortainerClient.throwForPullStatus(response: HttpResponse) {
    if (response.status.isSuccess()) return
    val status = response.status.value
    val text = try {
        response.bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ""
    }
    val message = messageOf(text)
    if (message != null && looksDenied(message)) throw PortainerException.Api(status, PULL_DENIED)
    when (status) {
        401, 403 -> throw PortainerException.Unauthorized()
        404 -> throw PortainerException.NotFound(message?.let { "Not found: $it" } ?: "Not found")
    }
    throw PortainerException.Api(status, message ?: response.status.description.ifBlank { "HTTP $status" })
}

private fun PortainerClient.messageOf(body: String): String? {
    val obj = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (e: Exception) {
        null
    }
    return ((obj?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: body.takeIf { obj == null })
        ?.takeIf { it.isNotBlank() }
}

private fun PortainerClient.streamError(line: String): String? {
    if (line.isBlank()) return null
    val obj = try {
        json.parseToJsonElement(line) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    val error = (obj["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val detail = ((obj["errorDetail"] as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return (error ?: detail)?.takeIf { it.isNotBlank() }
}
