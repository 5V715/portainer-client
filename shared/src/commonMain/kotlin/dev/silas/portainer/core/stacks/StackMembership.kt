package dev.silas.portainer.core.stacks

import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.recreate.isProtectedImage

/** Containers of the stack named [stackName] (running and stopped), by exact `com.docker.compose.project` label. */
fun stackContainers(stackName: String, containers: List<ContainerSummary>): List<ContainerSummary> =
    containers.filter { it.stack == stackName }

/**
 * True when the stack runs Portainer: a member container (running or stopped) or an image in its compose file
 * matches `isProtectedImage`. [resolvedImages] maps container id to `Config.Image` for members whose list image is
 * only an id (see `resolveIdOnlyImages`).
 */
fun stackProtection(
    containers: List<ContainerSummary>,
    composeImages: List<String>,
    resolvedImages: Map<String, String> = emptyMap(),
): Boolean =
    containers.any { isProtectedImage(resolvedImages[it.id] ?: it.image) } || composeImages.any(::isProtectedImage)
