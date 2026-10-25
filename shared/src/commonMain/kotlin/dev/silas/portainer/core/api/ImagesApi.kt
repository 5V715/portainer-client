package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.model.ImageSummary
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Registry-style denials are also what Docker reports for images that only exist locally, so the copy
 * shows Docker's own words and only suggests credentials.
 */
private fun pullDenied(dockerMessage: String) =
    "Image pull failed: ${dockerMessage.trimEnd('.')}. If the image is in a private registry, check its credentials in Registries."
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
 * Denial mapping: a stream error or any non-2xx body with a registry-style denial phrase gets the
 * "check its credentials in Registries" hint. Docker's text is redacted of the API key. A 401/403 without one (including Portainer RBAC "Access denied ...")
 * stays [PortainerException.Unauthorized], because it is ambiguous with a bad API key. A digest pins the pull.
 * [onProgress] gets one redacted line of text (`id: status progress`) per progress line of the stream.
 */
suspend fun PortainerClient.pullImage(endpointId: Int, ref: ImageRef, registryId: Int?, onProgress: (String) -> Unit = {}) {
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
                val obj = streamObject(line) ?: continue
                streamError(obj)?.let { throw pullFailure(200, it) }
                progressText(obj)?.let { onProgress(redact(it)) }
            }
        }
    } catch (e: Throwable) {
        throw mapTransportFailure(e)
    }
}

private fun PortainerClient.pullFailure(status: Int, text: String): PortainerException.Api {
    val safe = redact(text)
    return PortainerException.Api(status, if (looksDenied(safe)) pullDenied(safe) else safe)
}

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
    val message = messageOf(text)?.let(::redact)
    if (message != null && looksDenied(message)) throw pullFailure(status, message)
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

private fun PortainerClient.streamObject(line: String): JsonObject? {
    if (line.isBlank()) return null
    return try {
        json.parseToJsonElement(line) as? JsonObject
    } catch (e: Exception) {
        null
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/** `id: status progress` from one progress line, or null when it has no status. */
private fun progressText(obj: JsonObject): String? {
    val status = obj.text("status") ?: return null
    val id = obj.text("id")
    return listOfNotNull(status, obj.text("progress")).joinToString(" ").let { if (id != null) "$id: $it" else it }
}

private fun streamError(obj: JsonObject): String? {
    val error = (obj["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val detail = ((obj["errorDetail"] as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return (error ?: detail)?.takeIf { it.isNotBlank() }
}

@Serializable
private data class ImageDto(
    @SerialName("Id") val id: String = "",
    @SerialName("RepoTags") val repoTags: List<String>? = null,
    @SerialName("Size") val size: Long = 0,
    @SerialName("Created") val created: Long = 0,
    @SerialName("Labels") val labels: Map<String, String>? = null,
)

/** Lists local images (`GET images/json`, intermediate layers excluded). */
suspend fun PortainerClient.images(endpointId: Int): List<ImageSummary> {
    val response = execute(dockerPath(endpointId, "images/json"))
    return decodeBody(response) { response.body<List<ImageDto>>() }.map {
        ImageSummary(it.id, it.repoTags.orEmpty(), it.size, it.created, it.labels.orEmpty())
    }
}

/** Removes an image; [id] keeps its `sha256:` prefix. Docker's 409 (in use) surfaces as [PortainerException.Api]. */
suspend fun PortainerClient.removeImage(endpointId: Int, id: String, force: Boolean = false) {
    execute(dockerPath(endpointId, "images/$id")) {
        method = HttpMethod.Delete
        parameter("force", force)
    }
}
