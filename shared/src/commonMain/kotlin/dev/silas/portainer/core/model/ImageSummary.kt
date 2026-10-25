package dev.silas.portainer.core.model

import dev.silas.portainer.core.recreate.isProtectedImage

internal const val NONE_TAG = "<none>:<none>"

/** One entry of Docker's `GET images/json`. [created] is unix seconds. */
data class ImageSummary(
    val id: String = "",
    val repoTags: List<String> = emptyList(),
    val size: Long = 0,
    val created: Long = 0,
    val labels: Map<String, String> = emptyMap(),
) {
    /** No tag, or only Docker's `<none>:<none>` placeholder. */
    val dangling: Boolean get() = repoTags.all { it == NONE_TAG }
}

/** True when any tag is a Portainer server / agent image. */
fun ImageSummary.isProtected(): Boolean = repoTags.any(::isProtectedImage)
