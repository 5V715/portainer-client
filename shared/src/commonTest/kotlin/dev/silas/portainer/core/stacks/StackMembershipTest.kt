package dev.silas.portainer.core.stacks

import dev.silas.portainer.core.model.COMPOSE_PROJECT_LABEL
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackMembershipTest {
    private fun c(id: String, image: String, project: String?, state: ContainerState = ContainerState.Running) =
        ContainerSummary(
            id = id,
            name = id,
            image = image,
            state = state,
            statusText = "",
            health = null,
            stack = project,
            labels = project?.let { mapOf(COMPOSE_PROJECT_LABEL to it) }.orEmpty(),
        )

    @Test
    fun membersMatchTheProjectLabelExactlyRunningAndStopped() {
        val all = listOf(
            c("a", "nginx", "shop"),
            c("b", "redis", "shop", ContainerState.Exited),
            c("c", "nginx", "shop-dev"),
            c("d", "nginx", "Shop"),
            c("e", "nginx", null),
        )
        assertEquals(listOf("a", "b"), stackContainers("shop", all).map { it.id })
        assertTrue(stackContainers("none", all).isEmpty())
    }

    @Test
    fun protectedWhenAMemberRunsPortainerRunningOrStopped() {
        assertTrue(stackProtection(listOf(c("a", "portainer/agent:2.45.3", "p")), emptyList()))
        assertTrue(stackProtection(listOf(c("a", "docker.io/portainer/portainer-ce", "p", ContainerState.Exited)), emptyList()))
        assertFalse(stackProtection(listOf(c("a", "nginx", "p")), listOf("redis:7")))
    }

    @Test
    fun protectedWhenTheComposeFileUsesAPortainerImage() {
        assertTrue(stackProtection(emptyList(), listOf("nginx", "portainer/portainer-ce:latest")))
        assertFalse(stackProtection(emptyList(), emptyList()))
    }

    @Test
    fun idOnlyMemberImagesUseTheResolvedImage() {
        val id = "sha256:" + "a".repeat(64)
        val member = c("a", id, "p")
        assertFalse(stackProtection(listOf(member), emptyList()))
        assertTrue(stackProtection(listOf(member), emptyList(), mapOf("a" to "portainer/agent:latest")))
        assertFalse(stackProtection(listOf(member), emptyList(), mapOf("a" to "nginx")))
    }
}
