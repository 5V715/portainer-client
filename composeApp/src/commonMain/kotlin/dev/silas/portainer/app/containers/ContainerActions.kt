package dev.silas.portainer.app.containers

import dev.silas.portainer.core.model.ContainerState

enum class ContainerAction { Start, Stop, Restart, Remove }

/**
 * Which row-menu actions make sense for a container in [state]. Remove never forces, so it is only offered
 * where Docker accepts it: stopped containers, plus Unknown (the app can't tell, Docker decides).
 */
fun availableActions(state: ContainerState): List<ContainerAction> = when (state) {
    ContainerState.Running, ContainerState.Restarting, ContainerState.Paused ->
        listOf(ContainerAction.Stop, ContainerAction.Restart)
    ContainerState.Exited, ContainerState.Created, ContainerState.Dead ->
        listOf(ContainerAction.Start, ContainerAction.Remove)
    ContainerState.Removing -> listOf(ContainerAction.Start, ContainerAction.Stop, ContainerAction.Restart)
    ContainerState.Unknown -> ContainerAction.entries
}
