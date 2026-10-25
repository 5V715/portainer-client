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

/** Sum of the known sizes of some items; [unknownCount] items have no size (not counted in [knownBytes]). */
data class Recoverable(val knownBytes: Long, val knownCount: Int, val unknownCount: Int)

fun List<CleanupItem>.recoverable(): Recoverable {
    val known = mapNotNull { it.sizeBytes }
    return Recoverable(known.sum(), known.size, size - known.size)
}

/**
 * Fills volume sizes (by volume name) into the deletable items. Which items are selected never changes,
 * only their [CleanupItem.sizeBytes]; kept items are left as they are.
 */
fun CleanupPlan.withVolumeSizes(sizes: Map<String, Long?>): CleanupPlan {
    fun CleanupItem.sized() = if (sizeBytes == null) copy(sizeBytes = sizes[id]) else this
    return copy(toDelete = toDelete.map { it.sized() }, namedVolumes = namedVolumes.map { it.sized() })
}
