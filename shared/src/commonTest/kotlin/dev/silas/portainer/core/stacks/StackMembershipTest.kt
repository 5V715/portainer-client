package dev.silas.portainer.core.stacks

import dev.silas.portainer.core.model.COMPOSE_PROJECT_LABEL
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.EnvVar
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

    @Test
    fun composeImagesAreCheckedAsTheStackEnvResolvesThem() {
        val env = listOf(EnvVar("IMAGE", "portainer/agent"), EnvVar("TAG", "2.45.3"), EnvVar("EMPTY", ""))
        assertTrue(stackProtection(emptyList(), listOf("\${IMAGE}:\${TAG}"), env = env))
        assertTrue(stackProtection(emptyList(), listOf("\$IMAGE"), env = env))
        assertTrue(stackProtection(emptyList(), listOf("\${UNSET:-portainer/portainer-ce}:latest"), env = env))
        assertTrue(stackProtection(emptyList(), listOf("\${EMPTY:-docker.io/portainer/agent}"), env = env))
        assertFalse(stackProtection(emptyList(), listOf("\${EMPTY-portainer/agent}nginx"), env = env))
        assertFalse(stackProtection(emptyList(), listOf("\${IMAGE:-x}"), env = listOf(EnvVar("IMAGE", "nginx"))))
        assertFalse(stackProtection(emptyList(), listOf("\${IMAGE}"), env = emptyList()))
    }

    @Test
    fun interpolationFollowsComposeRules() {
        val env = listOf(EnvVar("A", "x"), EnvVar("E", ""))
        assertEquals("x:1", interpolateImage("\${A}:1", env))
        assertEquals("d", interpolateImage("\${E:-d}", env))
        assertEquals("", interpolateImage("\${E-d}", env))
        assertEquals("d", interpolateImage("\${U-d}", env))
        assertEquals("alt", interpolateImage("\${A:+alt}", env))
        assertEquals("", interpolateImage("\${E:+alt}", env))
        assertEquals("x", interpolateImage("\${A:?needed}", env))
        assertEquals("\$A", interpolateImage("\$\$A", env))
        assertEquals("nginx", interpolateImage("nginx", env))
    }
}

