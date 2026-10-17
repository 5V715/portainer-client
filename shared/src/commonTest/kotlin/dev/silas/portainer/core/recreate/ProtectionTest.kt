package dev.silas.portainer.core.recreate

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectionTest {
    @Test
    fun portainerImagesAreProtected() {
        for (image in listOf(
            "portainer/portainer-ce:2.21",
            "docker.io/portainer/agent",
            "portainer/portainer-ee@sha256:3f0e6d8b2a1c4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f",
            "portainer/portainer",
            "portainer/agent:2.21.4",
            "registry-1.docker.io/portainer/portainer-ce:latest",
            "index.docker.io/portainer/agent:2.21",
        )) assertTrue(isProtectedImage(image), image)
    }

    @Test
    fun otherImagesAreNotProtected() {
        for (image in listOf("nginx", "myportainer/x", "ghcr.io/portainer/portainer-ce", "portainer/helper", "library/nginx:1.27")) {
            assertFalse(isProtectedImage(image), image)
        }
    }
}
