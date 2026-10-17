package dev.silas.portainer.core.recreate

const val PROTECTED_IMAGE_MESSAGE = "This container runs Portainer. Changing it from this app would cut off the connection."

private val HUB_PREFIXES = listOf("docker.io/", "index.docker.io/")
private val PROTECTED_PREFIXES = listOf("portainer/portainer", "portainer/agent")

/** True for Portainer server / agent images (`portainer/portainer*`, `portainer/agent*`, optionally `docker.io/`-prefixed). */
fun isProtectedImage(image: String): Boolean {
    var ref = image.trim().lowercase()
    HUB_PREFIXES.firstOrNull { ref.startsWith(it) }?.let { ref = ref.removePrefix(it) }
    return PROTECTED_PREFIXES.any { ref.startsWith(it) }
}
