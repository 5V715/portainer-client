package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.model.RegistryInput
import dev.silas.portainer.core.model.RegistryType
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class RegistryDto(
    @SerialName("Id") val id: Int,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: Int = 0,
    @SerialName("URL") val url: String = "",
    @SerialName("Authentication") val authentication: Boolean = false,
    @SerialName("Username") val username: String? = null,
)

private fun RegistryDto.toRegistry() = Registry(
    id = id,
    name = name,
    type = RegistryType.fromCode(type),
    url = url,
    authentication = authentication,
    username = username?.takeIf { it.isNotEmpty() },
)

private fun RegistryInput.toJson(): JsonObject = buildJsonObject {
    put("Name", name)
    put("Type", type.code)
    put("URL", url)
    put("Authentication", authentication)
    if (authentication) {
        username?.let { put("Username", it) }
        password?.let { put("Password", it) }
    }
    baseUrl?.let { put("BaseURL", it) }
    ecrRegion?.let { put("Ecr", buildJsonObject { put("Region", it) }) }
}

suspend fun PortainerClient.registries(): List<Registry> =
    getJson<List<RegistryDto>>("/api/registries").map { it.toRegistry() }

suspend fun PortainerClient.createRegistry(input: RegistryInput): Registry {
    val response = execute("/api/registries") {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        setBody(input.toJson())
    }
    return decodeBody(response) { response.body<RegistryDto>().toRegistry() }
}

suspend fun PortainerClient.updateRegistry(id: Int, input: RegistryInput) {
    execute("/api/registries/$id") {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        setBody(input.toJson())
    }
}

suspend fun PortainerClient.deleteRegistry(id: Int) {
    execute("/api/registries/$id") { method = HttpMethod.Delete }
}
