package dev.silas.portainer.core.resources

import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceUsageTest {
    private fun c(
        name: String,
        state: ContainerState = ContainerState.Running,
        image: String = "app:1",
        imageId: String = "",
        volumes: List<String> = emptyList(),
        networks: List<String> = emptyList(),
    ) = ContainerSummary(name, name, image, state, "", null, null, emptyMap(), imageId, volumes, networks)

    private val running = c("web", imageId = "sha256:A", volumes = listOf("db"), networks = listOf("backend"))
    private val stopped = c("batch", ContainerState.Exited, imageId = "sha256:B", volumes = listOf("cache"))
    private val usage = ResourceUsage(listOf(running, stopped))

    @Test
    fun stoppedContainersCountAsUsers() {
        assertTrue(usage.isVolumeUsed("cache"))
        assertFalse(usage.isVolumeUsed("other"))
        assertTrue(usage.isVolumeUsed("db"))
    }

    @Test
    fun networkUsers() {
        assertEquals(listOf(running), usage.containersUsingNetwork("backend"))
        assertTrue(usage.isNetworkUsed("backend"))
        assertFalse(usage.isNetworkUsed("frontend"))
        assertEquals(emptyList(), usage.containersUsingNetwork("frontend"))
    }

    @Test
    fun imageMatchesByIdOrImageString() {
        assertEquals(listOf(running), usage.containersUsingImage("sha256:A"))
        assertTrue(usage.isImageUsed("sha256:B"))
        assertFalse(usage.isImageUsed("sha256:Z"))
        val byString = ResourceUsage(listOf(c("x", image = "sha256:Q")))
        assertTrue(byString.isImageUsed("sha256:Q"))
        assertFalse(ResourceUsage(listOf(c("y"))).isImageUsed(""))
    }

    @Test
    fun idOnlyImages() {
        assertTrue(isIdOnlyImage("sha256:" + "a".repeat(64)))
        assertTrue(isIdOnlyImage("A".repeat(64)))
        assertFalse(isIdOnlyImage("nginx:latest"))
        assertFalse(isIdOnlyImage("a".repeat(63)))
        assertFalse(isIdOnlyImage("portainer/portainer-ce"))
    }
}
