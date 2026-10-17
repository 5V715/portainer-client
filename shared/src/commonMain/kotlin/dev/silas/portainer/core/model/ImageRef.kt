package dev.silas.portainer.core.model

private const val DOCKER_HUB_HOST = "docker.io"

/** A parsed Docker image reference: `[host[:port]/]path[:tag][@digest]`. */
data class ImageRef(val name: String, val tag: String?, val digest: String?) {
    val registryHost: String
        get() {
            val first = name.substringBefore('/', missingDelimiterValue = "")
            return if (first.isNotEmpty() && (first.contains('.') || first.contains(':') || first == "localhost")) first else DOCKER_HUB_HOST
        }

    override fun toString(): String = buildString {
        append(name)
        if (tag != null) append(':').append(tag)
        if (digest != null) append('@').append(digest)
    }

    companion object {
        fun parse(ref: String): ImageRef {
            val digest = ref.substringAfter('@', "").ifEmpty { null }
            val rest = ref.substringBefore('@')
            val lastColon = rest.lastIndexOf(':')
            return if (lastColon > rest.lastIndexOf('/')) {
                ImageRef(rest.substring(0, lastColon), rest.substring(lastColon + 1).ifEmpty { null }, digest)
            } else {
                ImageRef(rest, null, digest)
            }
        }
    }
}
