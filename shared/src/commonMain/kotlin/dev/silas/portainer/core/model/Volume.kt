package dev.silas.portainer.core.model

private const val ANONYMOUS_LABEL = "com.docker.volume.anonymous"
private val HEX_NAME = Regex("[0-9a-f]{64}")

/** A Docker volume. [createdAt] is Docker's RFC3339 string, when reported. */
data class Volume(
    val name: String = "",
    val driver: String = "",
    val mountpoint: String = "",
    val scope: String = "",
    val createdAt: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val options: Map<String, String> = emptyMap(),
) {
    /** Created implicitly by a container (`VOLUME` in an image): labelled so, or named with 64 lowercase hex chars. */
    val anonymous: Boolean get() = ANONYMOUS_LABEL in labels || HEX_NAME.matches(name)
}
