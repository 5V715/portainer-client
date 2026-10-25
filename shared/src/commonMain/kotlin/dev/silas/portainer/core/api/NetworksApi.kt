package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.IpamSubnet
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.model.NetworkMember
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
private data class IpamConfigDto(
    @SerialName("Subnet") val subnet: String? = null,
    @SerialName("Gateway") val gateway: String? = null,
    @SerialName("IPRange") val ipRange: String? = null,
)

@Serializable
private data class IpamDto(@SerialName("Config") val config: List<IpamConfigDto>? = null)

@Serializable
private data class MemberDto(
    @SerialName("Name") val name: String = "",
    @SerialName("IPv4Address") val ipv4: String? = null,
    @SerialName("IPv6Address") val ipv6: String? = null,
)

@Serializable
private data class NetworkDto(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Driver") val driver: String = "",
    @SerialName("Scope") val scope: String = "",
    @SerialName("Internal") val internal: Boolean = false,
    @SerialName("Attachable") val attachable: Boolean = false,
    @SerialName("IPAM") val ipam: IpamDto? = null,
    @SerialName("Containers") val containers: Map<String, MemberDto>? = null,
    @SerialName("Labels") val labels: Map<String, String>? = null,
) {
    fun toModel() = Network(
        id, name, driver, scope, internal, attachable,
        ipam?.config.orEmpty().mapNotNull { c ->
            c.subnet?.takeIf { it.isNotBlank() }?.let { IpamSubnet(it, c.gateway?.ifBlank { null }, c.ipRange?.ifBlank { null }) }
        },
        labels.orEmpty(),
        containers.orEmpty().mapValues { (_, m) -> NetworkMember(m.name, m.ipv4?.ifBlank { null }, m.ipv6?.ifBlank { null }) },
    )
}

/** Input for [createNetwork]; the IPAM block is sent only when [subnet] is set. */
data class NetworkInput(
    val name: String,
    val driver: String = "bridge",
    val subnet: String? = null,
    val gateway: String? = null,
    val ipRange: String? = null,
    val internal: Boolean = false,
    val attachable: Boolean = false,
    val options: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
)

suspend fun PortainerClient.networks(endpointId: Int): List<Network> {
    val response = execute(dockerPath(endpointId, "networks"))
    return decodeBody(response) { response.body<List<NetworkDto>>() }.map { it.toModel() }
}

/** Inspects a network; unlike the list, this fills [Network.containers]. */
suspend fun PortainerClient.inspectNetwork(endpointId: Int, id: String): Network {
    val response = execute(dockerPath(endpointId, "networks/$id"))
    return decodeBody(response) { response.body<NetworkDto>() }.toModel()
}

/** Creates a network and returns its `Id`. Docker rejects duplicate names (`CheckDuplicate`). */
suspend fun PortainerClient.createNetwork(endpointId: Int, input: NetworkInput): String {
    val body = buildMap<String, JsonElement> {
        put("Name", JsonPrimitive(input.name.trim()))
        put("Driver", JsonPrimitive(input.driver))
        put("Internal", JsonPrimitive(input.internal))
        put("Attachable", JsonPrimitive(input.attachable))
        put("Options", JsonObject(input.options.mapValues { JsonPrimitive(it.value) }))
        put("Labels", JsonObject(input.labels.mapValues { JsonPrimitive(it.value) }))
        put("CheckDuplicate", JsonPrimitive(true))
        if (!input.subnet.isNullOrBlank()) {
            val pool = buildMap<String, JsonElement> {
                put("Subnet", JsonPrimitive(input.subnet.trim()))
                input.gateway?.takeIf { it.isNotBlank() }?.let { put("Gateway", JsonPrimitive(it.trim())) }
                input.ipRange?.takeIf { it.isNotBlank() }?.let { put("IPRange", JsonPrimitive(it.trim())) }
            }
            put("IPAM", JsonObject(mapOf("Driver" to JsonPrimitive("default"), "Config" to JsonArray(listOf(JsonObject(pool))))))
        }
    }
    val response = execute(dockerPath(endpointId, "networks/create")) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        setBody(JsonObject(body))
    }
    val created = decodeBody(response) { response.body<JsonObject>() }
    return (created["Id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw PortainerException.Api(response.status.value, "Unexpected response from Portainer")
}

/** Removes a network. Docker's 403 (predefined) / 409 (in use) surface as [PortainerException.Api]. */
suspend fun PortainerClient.removeNetwork(endpointId: Int, id: String) {
    execute(dockerPath(endpointId, "networks/$id")) { method = HttpMethod.Delete }
}
