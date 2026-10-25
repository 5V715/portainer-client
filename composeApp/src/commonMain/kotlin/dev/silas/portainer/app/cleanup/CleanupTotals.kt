package dev.silas.portainer.app.cleanup

import dev.silas.portainer.app.images.formatBytes
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.core.cleanup.Recoverable

/**
 * The grand-total line of a preview, over the selected items. Images use "Up to": an image's Docker size includes
 * layers shared with images that stay. Volume sizes come from `system/df` and are exact. Containers and networks
 * have no total (sizes are not meaningful).
 */
internal fun recoverableLine(kind: CleanupKind, r: Recoverable, pending: Boolean): String? {
    if (kind != CleanupKind.Images && kind != CleanupKind.Volumes) return null
    if (r.knownCount + r.unknownCount == 0) return null
    if (pending) return "Calculating sizes…"
    if (r.knownCount == 0) return "Size unknown"
    val size = formatBytes(r.knownBytes)
    return when {
        r.unknownCount > 0 && kind == CleanupKind.Images -> "Up to $size from known sizes (${r.unknownCount} unknown)"
        r.unknownCount > 0 -> "At least $size can be recovered (${r.unknownCount} ${if (r.unknownCount == 1) "size" else "sizes"} unknown)"
        kind == CleanupKind.Images -> "Up to $size can be recovered"
        else -> "$size can be recovered"
    }
}

internal fun namedSubtotal(r: Recoverable, pending: Boolean): String? = when {
    pending || r.knownCount + r.unknownCount == 0 -> null
    r.knownCount == 0 -> "Named volumes: size unknown"
    r.unknownCount > 0 -> "Named volumes: at least ${formatBytes(r.knownBytes)}"
    else -> "Named volumes: ${formatBytes(r.knownBytes)}"
}
