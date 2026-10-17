package dev.silas.portainer.core.logs

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.containerLogs
import dev.silas.portainer.core.platform.platformSupportsStreaming
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock

/** [tail] null means `all`. */
data class LogOptions(val tail: Int? = 200, val follow: Boolean = true, val since: Long? = null)

/**
 * Streaming platforms: one `follow=true` request, decoded batches as chunks arrive.
 * Others (wasm): an initial tail request, then a `since` poll every [pollIntervalMs],
 * dropping lines at or before the last seen timestamp.
 */
fun followLogs(
    client: PortainerClient,
    endpointId: Int,
    containerId: String,
    tty: Boolean,
    options: LogOptions,
    streaming: Boolean = platformSupportsStreaming,
    pollIntervalMs: Long = 2000,
): Flow<List<LogLine>> = if (streaming) {
    flow {
        val decoder = LogDecoder(tty)
        client.containerLogs(endpointId, containerId, options.copy(follow = true)).collect { chunk ->
            val lines = decoder.feed(chunk)
            if (lines.isNotEmpty()) emit(lines)
        }
        val rest = decoder.flush()
        if (rest.isNotEmpty()) emit(rest)
    }
} else {
    flow {
        var last: String? = null
        // tail=0 asks for no history; later polls must not turn that into "all", so anchor on start time.
        val anchor = if (options.tail == 0) options.since ?: Clock.System.now().epochSeconds else options.since
        while (true) {
            val seen = last
            val request = if (seen == null) {
                // Nothing timestamped yet: keep asking for the same window, never the whole history.
                LogOptions(if (options.tail == 0) null else options.tail, follow = false, since = anchor)
            } else {
                LogOptions(null, follow = false, since = rfc3339EpochSeconds(seen) ?: anchor)
            }
            val decoder = LogDecoder(tty)
            val lines = mutableListOf<LogLine>()
            client.containerLogs(endpointId, containerId, request).collect { lines += decoder.feed(it) }
            lines += decoder.flush()
            val boundary = last
            val fresh = if (boundary == null) lines else lines.filter { it.timestamp == null || it.timestamp > boundary }
            fresh.mapNotNull { it.timestamp }.maxOrNull()?.let { if (boundary == null || it > boundary) last = it }
            if (fresh.isNotEmpty()) emit(fresh)
            delay(pollIntervalMs)
        }
    }
}

/** Floor of the Unix time of an RFC3339 timestamp (`Z` or `±hh:mm`), or null if malformed; fraction is ignored. */
internal fun rfc3339EpochSeconds(ts: String): Long? = try {
    parseRfc3339(ts)
} catch (e: NumberFormatException) {
    null
} catch (e: IndexOutOfBoundsException) {
    null
}

private fun parseRfc3339(ts: String): Long {
    val year = ts.substring(0, 4).toInt()
    val month = ts.substring(5, 7).toInt()
    val day = ts.substring(8, 10).toInt()
    val hour = ts.substring(11, 13).toInt()
    val minute = ts.substring(14, 16).toInt()
    val second = ts.substring(17, 19).toInt()
    var i = 19
    if (i < ts.length && ts[i] == '.') {
        i++
        while (i < ts.length && ts[i].isDigit()) i++
    }
    var offset = 0L
    val zone = ts.substring(i)
    if (zone.length >= 6 && (zone[0] == '+' || zone[0] == '-')) {
        val sign = if (zone[0] == '-') -1 else 1
        offset = sign * (zone.substring(1, 3).toLong() * 3600 + zone.substring(4, 6).toLong() * 60)
    }
    // days-from-civil (Howard Hinnant)
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097L + doe - 719468
    return days * 86400 + hour * 3600 + minute * 60 + second - offset
}
