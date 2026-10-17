package dev.silas.portainer.app.containers

import dev.silas.portainer.core.model.ContainerState

enum class ContainerAction { Start, Stop, Restart, Remove }

/** Which row-menu actions make sense for a container in [state]. */
fun availableActions(state: ContainerState): List<ContainerAction> = when (state) {
    ContainerState.Running, ContainerState.Restarting, ContainerState.Paused ->
        listOf(ContainerAction.Stop, ContainerAction.Restart, ContainerAction.Remove)
    ContainerState.Exited, ContainerState.Created, ContainerState.Dead ->
        listOf(ContainerAction.Start, ContainerAction.Remove)
    else -> ContainerAction.entries
}
