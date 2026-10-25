package dev.silas.portainer.core.resources

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.model.ContainerSummary
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `Config.Image` for every container in [containers] (running or stopped) whose list image is only an id, keyed by
 * container id, for [ResourceProtection]. A failed inspect, or one that again shows only an id, leaves the
 * container out, so callers treat it as unresolved.
 */
suspend fun PortainerClient.resolveIdOnlyImages(endpointId: Int, containers: List<ContainerSummary>): Map<String, String> =
    containers.filter { isIdOnlyImage(it.image) }.mapNotNull { c ->
        val image = try {
            ((inspectContainer(endpointId, c.id)["Config"] as? JsonObject)?.get("Image") as? JsonPrimitive)?.content
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        image?.takeIf { it.isNotBlank() && !isIdOnlyImage(it) }?.let { c.id to it }
    }.toMap()
