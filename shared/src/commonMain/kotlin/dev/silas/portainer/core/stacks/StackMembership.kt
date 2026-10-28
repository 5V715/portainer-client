package dev.silas.portainer.core.stacks

import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.recreate.isProtectedImage

/** Containers of the stack named [stackName] (running and stopped), by exact `com.docker.compose.project` label. */
fun stackContainers(stackName: String, containers: List<ContainerSummary>): List<ContainerSummary> =
    containers.filter { it.stack == stackName }

/** `$$`, `${NAME}`, `${NAME<op>word}` (`:-`, `-`, `:?`, `?`, `:+`, `+`) and `$NAME`, as compose interpolates them. */
private val INTERPOLATION = Regex("""\$\$|\$\{([A-Za-z_][A-Za-z0-9_]*)(?:(:?[-?+])([^}]*))?\}|\$([A-Za-z_][A-Za-z0-9_]*)""")

/**
 * [image] as compose resolves it with [env] (the stack's variables): `${TAG}`, `${IMAGE:-portainer/agent}`, `$NAME`.
 * An unset variable without a default becomes empty, as in compose. The last of repeated names wins.
 */
fun interpolateImage(image: String, env: List<EnvVar>): String {
    if ('$' !in image) return image
    val values = env.associate { it.name to it.value }
    return INTERPOLATION.replace(image) { m ->
        if (m.value == "$$") return@replace "$"
        val name = m.groups[1]?.value ?: m.groups[4]?.value ?: return@replace m.value
        val value = values[name]
        val word = m.groups[3]?.value.orEmpty()
        when (m.groups[2]?.value) {
            ":-" -> value?.takeIf { it.isNotEmpty() } ?: word
            "-" -> value ?: word
            ":+" -> if (!value.isNullOrEmpty()) word else ""
            "+" -> if (value != null) word else ""
            else -> value.orEmpty() // plain, or `?`/`:?` (compose fails when unset: nothing deploys)
        }
    }
}

/**
 * True when the stack runs Portainer: a member container (running or stopped) or an image in its compose file
 * matches `isProtectedImage`. Compose file images are checked as compose resolves them with the stack's [env]
 * ([interpolateImage]). [resolvedImages] maps container id to `Config.Image` for members whose list image is only an id
 * (see `resolveIdOnlyImages`). Only the main compose file is read: images in a git stack's additional compose files
 * are not checked here; the containers they run are (members cover a running or stopped stack).
 */
fun stackProtection(
    containers: List<ContainerSummary>,
    composeImages: List<String>,
    resolvedImages: Map<String, String> = emptyMap(),
    env: List<EnvVar> = emptyList(),
): Boolean =
    containers.any { isProtectedImage(resolvedImages[it.id] ?: it.image) } || composeImagesProtected(composeImages, env)

/** An image of a compose file, as compose resolves it with [env], runs Portainer or its agent. */
fun composeImagesProtected(images: List<String>, env: List<EnvVar>): Boolean =
    images.any { isProtectedImage(it) || isProtectedImage(interpolateImage(it, env)) }
