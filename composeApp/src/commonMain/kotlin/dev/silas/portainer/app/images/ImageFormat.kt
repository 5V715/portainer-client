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

private val RFC3339 = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})?""")

/** Unix seconds from an RFC3339 timestamp such as Docker's `2026-10-01T10:00:00Z`; null when it doesn't parse. */
fun parseRfc3339(text: String?): Long? {
    val m = RFC3339.matchEntire(text?.trim().orEmpty()) ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    val zone = m.groupValues[7]
    val offset = if (zone.length == 6) {
        val sign = if (zone[0] == '-') -1 else 1
        sign * (zone.substring(1, 3).toLong() * 3600 + zone.substring(4, 6).toLong() * 60)
    } else {
        0L
    }
    return daysFromCivil(y.toLong(), mo.toLong(), d.toLong()) * DAY + h.toLong() * 3600 + mi.toLong() * 60 + s.toLong() - offset
}

/** Same wording as [formatCreated], for an RFC3339 timestamp; "unknown" when missing, the raw text when unparseable. */
fun formatCreatedRfc3339(createdAt: String?, nowEpochSeconds: Long): String {
    if (createdAt.isNullOrBlank()) return "unknown"
    val parsed = parseRfc3339(createdAt) ?: return createdAt
    return formatCreated(parsed, nowEpochSeconds)
}

/** Days since 1970-01-01 for a proleptic Gregorian date (inverse of [isoDate]). */
private fun daysFromCivil(year: Long, month: Long, day: Long): Long {
    val y = if (month <= 2) year - 1 else year
    val era = y.floorDiv(400L)
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}
