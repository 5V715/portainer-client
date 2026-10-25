package dev.silas.portainer.core.model

/**
 * `GET system/df`: volume sizes by name (null = unknown, Docker's -1). Image figures are not read: the cleanup's
 * "Up to" total uses the image list's sizes.
 */
data class DiskUsage(val volumeSizes: Map<String, Long?>)
