package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.Volume
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
private data class VolumeDto(
    @SerialName("Name") val name: String = "",
    @SerialName("Driver") val driver: String = "",
    @SerialName("Mountpoint") val mountpoint: String = "",
    @SerialName("Scope") val scope: String = "",
    @SerialName("CreatedAt") val createdAt: String? = null,
    @SerialName("Labels") val labels: Map<String, String>? = null,
    @SerialName("Options") val options: Map<String, String>? = null,
) {
    fun toModel() = Volume(name, driver, mountpoint, scope, createdAt, labels.orEmpty(), options.orEmpty())
}

@Serializable
private data class VolumeListDto(@SerialName("Volumes") val volumes: List<VolumeDto>? = null)

/** Lists volumes (`GET volumes`); Docker returns `"Volumes": null` when there are none. */
suspend fun PortainerClient.volumes(endpointId: Int): List<Volume> {
    val response = execute(dockerPath(endpointId, "volumes"))
    return decodeBody(response) { response.body<VolumeListDto>() }.volumes.orEmpty().map { it.toModel() }
}

suspend fun PortainerClient.inspectVolume(endpointId: Int, name: String): Volume {
    val response = execute(dockerPath(endpointId, "volumes/$name"))
    return decodeBody(response) { response.body<VolumeDto>() }.toModel()
}

/** Creates a volume; a null or blank [name] lets Docker generate one (an anonymous-style hex name). */
suspend fun PortainerClient.createVolume(
    endpointId: Int,
    name: String?,
    driver: String = "local",
    driverOpts: Map<String, String> = emptyMap(),
    labels: Map<String, String> = emptyMap(),
): Volume {
    val body = buildMap<String, JsonElement> {
        if (!name.isNullOrBlank()) put("Name", JsonPrimitive(name.trim()))
        put("Driver", JsonPrimitive(driver))
        if (driverOpts.isNotEmpty()) put("DriverOpts", JsonObject(driverOpts.mapValues { JsonPrimitive(it.value) }))
        if (labels.isNotEmpty()) put("Labels", JsonObject(labels.mapValues { JsonPrimitive(it.value) }))
    }
    val response = execute(dockerPath(endpointId, "volumes/create")) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        setBody(JsonObject(body))
    }
    return decodeBody(response) { response.body<VolumeDto>() }.toModel()
}

/** Removes a volume, never forced. Docker's 409 (in use) surfaces as [PortainerException.Api]. */
suspend fun PortainerClient.removeVolume(endpointId: Int, name: String) {
    execute(dockerPath(endpointId, "volumes/$name")) { method = HttpMethod.Delete }
}
