package dev.silas.portainer.core.registry

import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.model.RegistryType
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegistryMatchingTest {
    private val ghcr = Registry(1, "GH", RegistryType.GitHub, "https://ghcr.io/", true, "me")
    private val hub = Registry(2, "Hub", RegistryType.DockerHub, "docker.io", true, "me")
    private val local = Registry(3, "Local", RegistryType.Custom, "http://registry.local:5000/", false, null)
    private val all = listOf(ghcr, hub, local)

    @Test
    fun matchesByHost() {
        assertEquals(ghcr, matchRegistry(ImageRef.parse("ghcr.io/me/app"), all))
        assertEquals(local, matchRegistry(ImageRef.parse("registry.local:5000/t/app:1"), all))
    }

    @Test
    fun dockerHubMatchesDockerIo() {
        assertEquals(hub, matchRegistry(ImageRef.parse("nginx"), all))
        val otherHubUrl = hub.copy(url = "https://index.docker.io/v1/")
        assertEquals(otherHubUrl, matchRegistry(ImageRef.parse("me/app"), listOf(ghcr, otherHubUrl)))
    }

    @Test
    fun unknownHostIsNull() {
        assertNull(matchRegistry(ImageRef.parse("quay.io/x/y"), all))
        assertNull(matchRegistry(ImageRef.parse("nginx"), listOf(ghcr)))
    }

    @Test
    fun authHeaderIsBase64OfRegistryId() {
        assertEquals("""{"registryId":4}""", Base64.decode(registryAuthHeader(4)).decodeToString())
    }
}
