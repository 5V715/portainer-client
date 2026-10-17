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
    fun liveStatesOfferStopRestartButNotRemove() {
        // Remove never forces, so Docker would refuse it (409) for a live container.
        listOf(ContainerState.Running, ContainerState.Restarting, ContainerState.Paused).forEach {
            assertEquals(listOf(Stop, Restart), availableActions(it), it.name)
        }
    }

    @Test
    fun stoppedStatesOfferStartRemove() {
        listOf(ContainerState.Exited, ContainerState.Created, ContainerState.Dead).forEach {
            assertEquals(listOf(Start, Remove), availableActions(it), it.name)
        }
    }

    @Test
    fun unknownStateOffersEverything() {
        assertEquals(listOf(Start, Stop, Restart, Remove), availableActions(ContainerState.Unknown))
    }

    @Test
    fun removingStateDoesNotOfferRemove() {
        assertEquals(listOf(Start, Stop, Restart), availableActions(ContainerState.Removing))
    }
}
