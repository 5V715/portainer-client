package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.Endpoint
import dev.silas.portainer.core.model.EndpointType
import io.ktor.client.call.body
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class PortainerStatus(val version: String)

@Serializable
private data class StatusDto(@SerialName("Version") val version: String)

@Serializable
private data class EndpointDto(
    @SerialName("Id") val id: Int,
    @SerialName("Name") val name: String,
    @SerialName("Type") val type: Int,
    @SerialName("Status") val status: Int = 0,
)

suspend fun PortainerClient.status(): PortainerStatus =
    PortainerStatus(execute("/api/status").body<StatusDto>().version)

suspend fun PortainerClient.endpoints(): List<Endpoint> =
    execute("/api/endpoints").body<List<EndpointDto>>().map {
        Endpoint(it.id, it.name, EndpointType.fromCode(it.type), online = it.status == 1)
    }
