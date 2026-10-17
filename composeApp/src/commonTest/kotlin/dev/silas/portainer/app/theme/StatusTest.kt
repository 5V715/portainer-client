package dev.silas.portainer.app.theme

import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.Health
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusTest {
    @Test
    fun healthOverridesRunning() {
        assertEquals(Status("healthy", StatusTone.Good), statusOf(ContainerState.Running, Health.Healthy))
        assertEquals(Status("unhealthy", StatusTone.Bad), statusOf(ContainerState.Running, Health.Unhealthy))
        assertEquals(Status("starting", StatusTone.Warn), statusOf(ContainerState.Running, Health.Starting))
        assertEquals(Status("running", StatusTone.Good), statusOf(ContainerState.Running, null))
    }

    @Test
    fun otherStatesUseTheirName() {
        assertEquals(Status("exited", StatusTone.Idle), statusOf(ContainerState.Exited, null))
        assertEquals(Status("paused", StatusTone.Warn), statusOf(ContainerState.Paused, null))
        assertEquals(Status("restarting", StatusTone.Warn), statusOf(ContainerState.Restarting, null))
    }
}
