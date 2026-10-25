package dev.silas.portainer.core.model

/** Per-image figures of Docker's `GET system/df`; [sharedSize] is null when Docker reports -1 (not computed). */
data class ImageDiskUsage(val size: Long, val sharedSize: Long?)

/** `GET system/df`: volume sizes by name (null = unknown, Docker's -1) and image figures by id. */
data class DiskUsage(val volumeSizes: Map<String, Long?>, val images: Map<String, ImageDiskUsage>)
