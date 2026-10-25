package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.EngineInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class VersionDto(@SerialName("ApiVersion") val apiVersion: String = "")

@Serializable
private data class SwarmDto(@SerialName("LocalNodeState") val localNodeState: String = "")

@Serializable
private data class InfoDto(@SerialName("Swarm") val swarm: SwarmDto? = null)

suspend fun PortainerClient.engineInfo(endpointId: Int): EngineInfo {
    val version = getJson<VersionDto>(dockerPath(endpointId, "version"))
    val info = getJson<InfoDto>(dockerPath(endpointId, "info"))
    return EngineInfo(version.apiVersion, info.swarm?.localNodeState == "active")
}
