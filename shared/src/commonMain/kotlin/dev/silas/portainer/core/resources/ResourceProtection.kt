package dev.silas.portainer.core.resources

import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.ImageSummary
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.model.isProtected
import dev.silas.portainer.core.recreate.isProtectedImage

/** Name of the volume that holds Portainer's own data; never removed. */
const val PORTAINER_DATA_VOLUME = "portainer_data"

/**
 * Decides what the app must never remove. [resolvedImages] maps container id to its `Config.Image`
 * for containers whose list `image` is only an id (`sha256:...` / 64 hex), so they can still be recognised.
 */
class ResourceProtection(
    private val containers: List<ContainerSummary>,
    private val resolvedImages: Map<String, String> = emptyMap(),
) {
    private val protectedContainers: List<ContainerSummary> = containers.filter(::isContainerProtected)

    fun isContainerProtected(c: ContainerSummary): Boolean = isProtectedImage(resolvedImages[c.id] ?: c.image)

    fun isImageProtected(image: ImageSummary): Boolean =
        image.isProtected() ||
            (image.id.isNotEmpty() && protectedContainers.any { it.imageId == image.id || it.image == image.id })

    fun isVolumeProtected(name: String): Boolean =
        name == PORTAINER_DATA_VOLUME || protectedContainers.any { name in it.volumeNames }

    fun isNetworkProtected(network: Network): Boolean =
        network.predefined || protectedContainers.any { network.name in it.networkNames }
}
