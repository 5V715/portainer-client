package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.DiskUsage
import io.ktor.client.call.body
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class DfUsageDto(@SerialName("Size") val size: Long = -1)

@Serializable
private data class DfVolumeDto(@SerialName("Name") val name: String = "", @SerialName("UsageData") val usage: DfUsageDto? = null)

@Serializable
private data class DfDto(
    @SerialName("Volumes") val volumes: List<DfVolumeDto>? = null,
)

/** Disk usage (`GET system/df`). Can be slow on hosts with a lot of data; Docker reports -1 for sizes it did not compute. */
suspend fun PortainerClient.systemDf(endpointId: Int): DiskUsage {
    val response = execute(dockerPath(endpointId, "system/df"))
    val dto = decodeBody(response) { response.body<DfDto>() }
    return DiskUsage(
        volumeSizes = dto.volumes.orEmpty().associate { v -> v.name to v.usage?.size?.takeIf { it >= 0 } },
    )
}
