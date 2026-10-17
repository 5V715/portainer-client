package dev.silas.portainer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageRefTest {
    private fun check(ref: String, name: String, tag: String?, digest: String?, host: String) {
        val p = ImageRef.parse(ref)
        assertEquals(name, p.name, ref)
        assertEquals(tag, p.tag, ref)
        assertEquals(digest, p.digest, ref)
        assertEquals(host, p.registryHost, ref)
        assertEquals(ref, p.toString())
    }

    @Test
    fun table() {
        check("nginx", "nginx", null, null, "docker.io")
        check("nginx:1.27", "nginx", "1.27", null, "docker.io")
        check("ghcr.io/me/app:2", "ghcr.io/me/app", "2", null, "ghcr.io")
        check("registry.local:5000/team/app", "registry.local:5000/team/app", null, null, "registry.local:5000")
        check("localhost/app:dev", "localhost/app", "dev", null, "localhost")
        check("me/app@sha256:abc", "me/app", null, "sha256:abc", "docker.io")
        check("ghcr.io/me/app:2@sha256:abc", "ghcr.io/me/app", "2", "sha256:abc", "ghcr.io")
    }

    @Test
    fun portInHostIsNotATag() {
        assertNull(ImageRef.parse("registry.local:5000/app").tag)
    }
}
