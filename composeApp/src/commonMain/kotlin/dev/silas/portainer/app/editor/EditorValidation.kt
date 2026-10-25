package dev.silas.portainer.app.editor

import dev.silas.portainer.app.networks.isIpv4
import dev.silas.portainer.app.networks.isIpv6
import dev.silas.portainer.app.networks.isValidIp
import dev.silas.portainer.core.editor.MountType
import dev.silas.portainer.core.editor.duplicateLabelKeys

/** Form sections as the screen shows them; validation messages are grouped by these. */
enum class EditorSection { Image, General, Ports, Volumes, Networks, Env, Labels, Restart, Resources }

private const val MAX_PORT = 65535
private const val NANOS_PER_CPU = 1_000_000_000L
private const val CPU_FRACTION_DIGITS = 9
private const val MEMORY_HINT = "use bytes or a size like 512m or 1g"

private val MEMORY = Regex("""^(\d+)(?:\.(\d+))?\s*([kmgt])?(?:i?b)?$""", RegexOption.IGNORE_CASE)
private val CPUS = Regex("""^(\d*)(?:\.(\d{1,9}))?$""")
private val TOO_PRECISE_CPUS = Regex("""^\d*\.\d{10,}$""")

/** Docker's smallest memory limit. */
private const val MIN_MEMORY = 6L shl 20
private val CONTAINER_NAME = Regex("""^[a-zA-Z0-9][a-zA-Z0-9_.-]+$""")
private val MAC = Regex("""^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$""")
private val DRIVE_PATH = Regex("""^[A-Za-z]:[\\/]""")
private val UNITS = listOf('t' to (1L shl 40), 'g' to (1L shl 30), 'm' to (1L shl 20), 'k' to (1L shl 10))

/** `512m`, `1g`, `1.5g`, `1048576` (bytes), case-insensitive, optional `b`/`ib`; `null` when it doesn't parse. */
fun parseMemory(text: String): Long? {
    val match = MEMORY.matchEntire(text.trim()) ?: return null
    val (whole, fraction, unit) = match.destructured
    if (unit.isEmpty()) return if (fraction.isEmpty()) whole.toLongOrNull() else null
    val multiplier = UNITS.first { it.first == unit.lowercase()[0] }.second
    val wholeValue = whole.toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / multiplier } ?: return null
    val fractionValue = if (fraction.isEmpty()) 0L else ("0.$fraction".toDouble() * multiplier).toLong()
    return wholeValue * multiplier + fractionValue
}

/** The largest unit that divides [bytes] exactly (`512m`, `1g`), else plain bytes. */
fun formatMemory(bytes: Long): String {
    val (unit, size) = UNITS.firstOrNull { bytes % it.second == 0L } ?: return bytes.toString()
    return "${bytes / size}$unit"
}

/** Cores as a decimal (`1.5`, `.25`) in 1e-9 CPUs; `null` when it doesn't parse. */
fun parseCpus(text: String): Long? {
    val match = CPUS.matchEntire(text.trim()) ?: return null
    val (whole, fraction) = match.destructured
    if (whole.isEmpty() && fraction.isEmpty()) return null
    val wholeValue = (whole.ifEmpty { "0" }).toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / NANOS_PER_CPU } ?: return null
    return wholeValue * NANOS_PER_CPU + fraction.padEnd(CPU_FRACTION_DIGITS, '0').toLong()
}

fun formatCpus(nanos: Long): String {
    val whole = nanos / NANOS_PER_CPU
    val fraction = (nanos % NANOS_PER_CPU).toString().padStart(CPU_FRACTION_DIGITS, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}

internal fun parseShares(text: String): Long? = text.trim().takeIf { t -> t.isNotEmpty() && t.all { it in '0'..'9' } }?.toLongOrNull()

internal fun parseRetries(text: String): Int? = text.trim().takeIf { t -> t.isNotEmpty() && t.all { it in '0'..'9' } }?.toIntOrNull()

/** Why [name] can't be a container name, or null. */
internal fun containerNameProblem(name: String): String? = when {
    name.isEmpty() -> "Name is required"
    !CONTAINER_NAME.matches(name) -> "Name: use at least 2 letters, digits, '_', '.' or '-', starting with a letter or digit"
    else -> null
}

/** Linux absolute path, Windows drive path or UNC / named pipe. */
internal fun isAbsolutePath(s: String) = s.startsWith("/") || DRIVE_PATH.containsMatchIn(s) || s.startsWith("\\\\")

private fun isPort(s: String) = s.toIntOrNull()?.let { it in 1..MAX_PORT } == true && s.all { it in '0'..'9' }

/** Blank (random), a port, or a `low-high` range. */
private fun isHostPort(s: String): Boolean {
    if (s.isEmpty() || isPort(s)) return true
    val parts = s.split('-')
    return parts.size == 2 && parts.all(::isPort) && parts[0].toInt() <= parts[1].toInt()
}

/**
 * Messages per section; empty when the draft can be finished. Port, mount and network rows equal to a row of
 * [initial] aren't checked: they are kept exactly as Docker already accepted them.
 */
fun validate(draft: EditorDraft, initial: EditorDraft, mode: EditorMode): Map<EditorSection, List<String>> = buildMap {
    fun section(s: EditorSection, block: MutableList<String>.() -> Unit) {
        val messages = buildList(block)
        if (messages.isNotEmpty()) put(s, messages)
    }

    section(EditorSection.Image) { if (draft.image.isBlank()) add("Image is required") }

    section(EditorSection.General) {
        val name = draft.name.trim()
        // New: a blank name lets Docker pick one.
        if (name.isNotEmpty() || mode == EditorMode.Edit) containerNameProblem(name)?.let(::add)
    }

    section(EditorSection.Ports) {
        draft.ports.forEachIndexed { i, p ->
            if (p in initial.ports) return@forEachIndexed
            val n = i + 1
            if (!isPort(p.containerPort.trim())) add("Port $n: container port must be 1–65535")
            if (!isHostPort(p.hostPort.trim())) add("Port $n: host port must be empty or 1–65535")
            if (p.hostIp.isNotBlank() && !isValidIp(p.hostIp.trim())) add("Port $n: host IP is not a valid IP address")
        }
    }

    section(EditorSection.Volumes) {
        draft.mounts.forEachIndexed { i, m ->
            if (m in initial.mounts) return@forEachIndexed
            val n = i + 1
            if (!isAbsolutePath(m.target)) add("Mount $n: container path must be an absolute path")
            if (m.target.isNotEmpty() && draft.mounts.withIndex().any { (j, o) -> j != i && o.target == m.target }) add("Mount $n: ${m.target} is already mounted")
            when (m.type) {
                MountType.Bind -> if (!isAbsolutePath(m.source)) add("Mount $n: host path must be an absolute path")
                MountType.Volume -> if (isAbsolutePath(m.source)) add("Mount $n: a volume name can't be a path")
                MountType.Tmpfs -> Unit
            }
        }
    }

    section(EditorSection.Networks) {
        if (draft.networkModeReadOnly != null) return@section
        val names = draft.networks.map { it.name.trim() }
        draft.networks.forEachIndexed { i, net ->
            val n = i + 1
            val name = names[i]
            if (name.isEmpty()) add("Network $n: choose a network")
            if (name.isNotEmpty() && names.indexOf(name) != i) add("Network $n: $name is listed twice")
            if ((name == "host" || name == "none") && draft.networks.size > 1) add("Network $n: $name can't be combined with other networks")
            if (net in initial.networks || isHostOrNone(name)) return@forEachIndexed
            if (net.ipv4.isNotBlank() && !isIpv4(net.ipv4.trim())) add("Network $n: IPv4 address is not valid")
            if (net.ipv6.isNotBlank() && !isIpv6(net.ipv6.trim())) add("Network $n: IPv6 address is not valid")
            if (net.mac.isNotBlank() && !MAC.matches(net.mac.trim())) add("Network $n: MAC address is not valid")
        }
    }

    section(EditorSection.Env) {
        draft.env.forEachIndexed { i, e -> if (envKey(e).isBlank() && envValue(e).isNotEmpty()) add("Variable ${i + 1}: name is required") }
        // A loaded env with duplicate keys is kept as is; only an edited one is checked.
        if (draft.env != initial.env) draft.env.map(::envKey).filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.forEach { add("Duplicate variable: $it") }
    }

    section(EditorSection.Labels) {
        draft.labels.forEachIndexed { i, (k, v) -> if (k.isBlank() && v.isNotEmpty()) add("Label ${i + 1}: key is required") }
        duplicateLabelKeys(draft.labels.filter { it.first.isNotBlank() }).forEach { add("Duplicate label: $it") }
    }

    section(EditorSection.Restart) {
        if (draft.restartName == "on-failure" && parseRetries(draft.maxRetries) == null) {
            add("Maximum retries must be a whole number, 0 or more")
        }
    }

    section(EditorSection.Resources) {
        if (draft.memory.isNotBlank() && parseMemory(draft.memory) == null) add("Memory limit: $MEMORY_HINT")
        if (draft.memoryReservation.isNotBlank() && parseMemory(draft.memoryReservation) == null) add("Memory reservation: $MEMORY_HINT")
        // Docker's own limits; loaded values it already accepted aren't rechecked.
        if (draft.memory != initial.memory || draft.memoryReservation != initial.memoryReservation) {
            val limit = parseMemory(draft.memory)?.takeIf { it > 0 }
            val reservation = parseMemory(draft.memoryReservation)?.takeIf { it > 0 }
            if (limit != null && limit < MIN_MEMORY) add("Memory limit must be at least 6m")
            if (limit != null && reservation != null && reservation > limit) add("Memory reservation can't be more than the memory limit")
        }
        when {
            draft.cpus.isBlank() -> Unit
            TOO_PRECISE_CPUS.matches(draft.cpus.trim()) -> add("CPU limit: use at most 9 decimal places")
            (parseCpus(draft.cpus) ?: 0L) <= 0L -> add("CPU limit must be a number of cores greater than 0")
        }
        if (draft.cpuShares.isNotBlank() && (parseShares(draft.cpuShares) ?: 0L) <= 0L) add("CPU shares must be a whole number greater than 0")
    }
}
