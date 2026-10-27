package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.Endpoint
import dev.silas.portainer.core.model.EndpointType
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
    @SerialName("Snapshots") val snapshots: List<SnapshotDto>? = null,
)

@Serializable
private data class SnapshotDto(@SerialName("Swarm") val swarm: Boolean = false)

suspend fun PortainerClient.status(): PortainerStatus =
    PortainerStatus(getJson<StatusDto>("/api/status").version)

suspend fun PortainerClient.endpoints(): List<Endpoint> =
    getJson<List<EndpointDto>>("/api/endpoints").map {
        Endpoint(it.id, it.name, EndpointType.fromCode(it.type), online = it.status == 1, swarm = it.snapshots.orEmpty().any { s -> s.swarm })
    }
