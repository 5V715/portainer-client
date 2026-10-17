package dev.silas.portainer.app.containers

import dev.silas.portainer.app.containers.ContainerAction.Remove
import dev.silas.portainer.app.containers.ContainerAction.Restart
import dev.silas.portainer.app.containers.ContainerAction.Start
import dev.silas.portainer.app.containers.ContainerAction.Stop
import dev.silas.portainer.core.model.ContainerState
import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerActionsTest {
    @Test
    fun liveStatesOfferStopRestartRemove() {
        listOf(ContainerState.Running, ContainerState.Restarting, ContainerState.Paused).forEach {
            assertEquals(listOf(Stop, Restart, Remove), availableActions(it), it.name)
        }
    }

    @Test
    fun stoppedStatesOfferStartRemove() {
        listOf(ContainerState.Exited, ContainerState.Created, ContainerState.Dead).forEach {
            assertEquals(listOf(Start, Remove), availableActions(it), it.name)
        }
    }

    @Test
    fun unknownStatesOfferEverything() {
        listOf(ContainerState.Unknown, ContainerState.Removing).forEach {
            assertEquals(listOf(Start, Stop, Restart, Remove), availableActions(it), it.name)
        }
    }
}
