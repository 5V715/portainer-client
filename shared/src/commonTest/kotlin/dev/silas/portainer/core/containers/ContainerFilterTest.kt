package dev.silas.portainer.core.containers

import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerFilterTest {
    private fun c(name: String, image: String, state: ContainerState, stack: String? = null) =
        ContainerSummary(name, name, image, state, "", null, stack, emptyMap())

    private val all = listOf(
        c("web", "nginx:1.27", ContainerState.Running),
        c("Batch", "alpine:3.20", ContainerState.Exited),
        c("jellyfin", "jellyfin/jellyfin", ContainerState.Running, "media"),
        c("api", "team/api:2", ContainerState.Running),
    )

    @Test
    fun emptyFilterReturnsAllSortedByName() {
        assertEquals(listOf("api", "Batch", "jellyfin", "web"), all.applyFilter(ContainerFilter()).map { it.name })
    }

    @Test
    fun queryMatchesImageCaseInsensitively() {
        assertEquals(listOf("web"), all.applyFilter(ContainerFilter(query = "NGINX")).map { it.name })
    }

    @Test
    fun queryMatchesName() {
        assertEquals(listOf("Batch"), all.applyFilter(ContainerFilter(query = "batch")).map { it.name })
    }

    @Test
    fun statesFilter() {
        assertEquals(listOf("Batch"), all.applyFilter(ContainerFilter(states = setOf(ContainerState.Exited))).map { it.name })
    }

    @Test
    fun stackFilter() {
        assertEquals(listOf("jellyfin"), all.applyFilter(ContainerFilter(stack = "media")).map { it.name })
    }
}
