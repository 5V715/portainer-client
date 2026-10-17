package dev.silas.portainer.core.registry

import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.model.RegistryType
import kotlin.io.encoding.Base64

private val DOCKER_HUB_HOSTS = setOf("docker.io", "index.docker.io", "registry-1.docker.io", "registry.hub.docker.com")

private fun hostOf(url: String): String =
    url.trim().substringAfter("://").substringBefore('/').lowercase()

fun matchRegistry(ref: ImageRef, registries: List<Registry>): Registry? {
    val host = ref.registryHost.lowercase()
    return registries.firstOrNull { r ->
        val h = hostOf(r.url)
        h == host || (r.type == RegistryType.DockerHub && host in DOCKER_HUB_HOSTS) ||
            (host == "docker.io" && h in DOCKER_HUB_HOSTS)
    }
}

/** Value for `X-Registry-Auth`: Portainer resolves the stored credentials by registry id. */
fun registryAuthHeader(registryId: Int): String =
    Base64.encode("""{"registryId":$registryId}""".encodeToByteArray())
