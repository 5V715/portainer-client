package dev.silas.portainer.app.images

private const val DAY = 86_400L

/** Docker-style (1000-based) size: "999 B", "142 MB", "1.5 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val tenths = (value * 10).toLong() // truncate
    val text = if (value >= 100 || tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    return "$text ${units[unit]}"
}

/** "today", "yesterday", "N days ago" below 30 days, otherwise the UTC date as yyyy-MM-dd. */
fun formatCreated(createdEpochSeconds: Long, nowEpochSeconds: Long): String {
    if (createdEpochSeconds <= 0) return "unknown"
    val days = (nowEpochSeconds - createdEpochSeconds) / DAY
    return when {
        days < 1 -> "today"
        days == 1L -> "yesterday"
        days < 30 -> "$days days ago"
        else -> isoDate(createdEpochSeconds)
    }
}

/** yyyy-MM-dd (UTC) from unix seconds, via the civil-from-days algorithm. */
internal fun isoDate(epochSeconds: Long): String {
    val z = (epochSeconds).floorDiv(DAY) + 719_468
    val era = (z).floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}
