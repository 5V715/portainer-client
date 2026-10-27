package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.StackDetail
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.stacks
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Stack
import kotlinx.coroutines.CancellationException

/**
 * The route to the stack [container] belongs to, or null for a non-member. [stacks] are the endpoint's stacks
 * (managed and external); an external one links by name only (`stackId = null`).
 */
fun stackLinkFor(container: ContainerSummary, stacks: List<Stack>, serverId: String): StackDetail? =
    stackLinkFor(container.stack, stacks, serverId)

/** As above, from the Compose project name alone (what the inspect-based screens have). */
fun stackLinkFor(project: String?, stacks: List<Stack>, serverId: String): StackDetail? {
    if (project.isNullOrBlank()) return null
    val stack = stacks.firstOrNull { it.name == project } ?: return null
    return StackDetail(serverId, stack.endpointId, stack.id, stack.name)
}

/**
 * Looks the stack up for the "Open stack" button. The button is a convenience: a failed load (or a container
 * outside any stack) just means no button, never an error.
 */
suspend fun loadStackLink(client: PortainerClient, serverId: String, endpointId: Int, project: String?): StackDetail? {
    if (project.isNullOrBlank()) return null
    return try {
        stackLinkFor(project, client.stacks(endpointId), serverId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
