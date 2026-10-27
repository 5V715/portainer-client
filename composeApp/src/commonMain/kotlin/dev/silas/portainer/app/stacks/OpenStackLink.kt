package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.StackDetail
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.stacks
import dev.silas.portainer.core.model.Stack
import kotlinx.coroutines.CancellationException

/**
 * The route to the stack of Compose project [project] on [serverId], or null for a non-member. [stacks] are the
 * endpoint's stacks (managed and external); an external one links by name only (`stackId = null`).
 */
fun stackLinkFor(project: String?, stacks: List<Stack>, serverId: String, endpointId: Int): StackDetail? {
    if (project.isNullOrBlank()) return null
    val stack = stacks.firstOrNull { it.name == project && it.endpointId == endpointId } ?: return null
    return StackDetail(serverId, endpointId, stack.id, stack.name)
}

/** The endpoint's stacks for "Open stack"; a failed load (the button is a convenience) gives an empty list. */
suspend fun loadStacks(client: PortainerClient, endpointId: Int): List<Stack> =
    try {
        client.stacks(endpointId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

/** The route to the stack of [project], looked up once; null for a non-member or when the stacks can't be loaded. */
suspend fun loadStackLink(client: PortainerClient, serverId: String, endpointId: Int, project: String?): StackDetail? =
    if (project.isNullOrBlank()) null else stackLinkFor(project, loadStacks(client, endpointId), serverId, endpointId)
