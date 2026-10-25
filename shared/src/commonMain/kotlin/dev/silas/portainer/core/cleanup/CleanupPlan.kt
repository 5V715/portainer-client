package dev.silas.portainer.core.cleanup

enum class CleanupKind { Containers, Images, Volumes, Networks }

/** [id] is what gets deleted: container id, image id, volume name or network id. */
data class CleanupItem(val id: String, val label: String, val sizeBytes: Long?)

/**
 * [toDelete] is what the preview selects; [namedVolumes] are unused named volumes, deleted only when the
 * user opts in (Volumes only); [kept] are protected Portainer items shown as "Kept (Portainer)".
 */
data class CleanupPlan(
    val kind: CleanupKind,
    val toDelete: List<CleanupItem>,
    val namedVolumes: List<CleanupItem> = emptyList(),
    val kept: List<CleanupItem> = emptyList(),
)
