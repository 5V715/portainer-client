package dev.silas.portainer.core.resources

import dev.silas.portainer.core.model.ContainerSummary

/** Which containers (running or stopped) reference which images, volumes and networks. */
class ResourceUsage(containers: List<ContainerSummary>) {
    private val all = containers

    /** Matches by full image id; also by the container's `image` string, which sometimes shows the id. */
    fun containersUsingImage(imageId: String): List<ContainerSummary> =
        if (imageId.isEmpty()) emptyList() else all.filter { it.imageId == imageId || it.image == imageId }

    fun containersUsingVolume(name: String): List<ContainerSummary> = all.filter { name in it.volumeNames }

    fun containersUsingNetwork(name: String): List<ContainerSummary> = all.filter { name in it.networkNames }

    fun isImageUsed(imageId: String): Boolean = containersUsingImage(imageId).isNotEmpty()

    fun isVolumeUsed(name: String): Boolean = containersUsingVolume(name).isNotEmpty()

    fun isNetworkUsed(name: String): Boolean = containersUsingNetwork(name).isNotEmpty()
}
