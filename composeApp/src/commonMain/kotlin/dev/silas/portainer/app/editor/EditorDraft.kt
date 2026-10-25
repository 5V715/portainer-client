package dev.silas.portainer.app.editor

import dev.silas.portainer.core.editor.ContainerForm
import dev.silas.portainer.core.editor.MountRow
import dev.silas.portainer.core.editor.MountType
import dev.silas.portainer.core.editor.NetworkRow
import dev.silas.portainer.core.editor.PortRow
import dev.silas.portainer.core.editor.RestartPolicyForm
import dev.silas.portainer.core.editor.joinCommandLine
import dev.silas.portainer.core.editor.splitCommandLine

/** A port row as typed; [containerPort] is text so an invalid entry can be shown and flagged. */
data class PortDraft(val hostIp: String, val hostPort: String, val containerPort: String, val protocol: String)

/** A network row as typed; [aliases] is a comma-separated list. */
data class NetworkDraft(val name: String, val aliases: String, val ipv4: String, val ipv6: String, val mac: String)

/**
 * The form exactly as the user sees it: typed values as text. [toForm] turns it into a [ContainerForm];
 * [networks] holds the primary network first.
 */
data class EditorDraft(
    val image: String,
    val name: String,
    val command: String,
    val entrypoint: String,
    val workingDir: String,
    val user: String,
    val ports: List<PortDraft>,
    val publishAll: Boolean,
    val mounts: List<MountRow>,
    val networks: List<NetworkDraft>,
    val networkModeReadOnly: String?,
    /** `K=V` entries; an entry without `=` is kept as-is. */
    val env: List<String>,
    val labels: List<Pair<String, String>>,
    val restartName: String,
    val maxRetries: String,
    val memory: String,
    val memoryReservation: String,
    val cpus: String,
    val cpuShares: String,
) {
    /**
     * The typed form. Text fields still equal to [initial] keep [before]'s value exactly (e.g. a `null` vs empty
     * command); a value that doesn't parse also keeps [before]'s, since validation blocks finishing anyway.
     * Blank env and label rows are left out.
     */
    fun toForm(before: ContainerForm, initial: EditorDraft): ContainerForm {
        val networkRows = if (networkModeReadOnly != null) {
            emptyList()
        } else {
            networks.map {
                val name = it.name.trim()
                // host and none take no endpoint settings; whatever is left in the hidden fields is dropped.
                if (isHostOrNone(name)) NetworkRow(name, emptyList(), "", "", "")
                else NetworkRow(name, splitAliases(it.aliases), it.ipv4.trim(), it.ipv6.trim(), it.mac.trim())
            }
        }
        return ContainerForm(
            image = image.trim(),
            name = name.trim(),
            command = args(command, initial.command, before.command),
            entrypoint = args(entrypoint, initial.entrypoint, before.entrypoint),
            workingDir = workingDir.trim(),
            user = user.trim(),
            ports = ports.map { PortRow(it.hostIp.trim(), it.hostPort.trim(), it.containerPort.trim().toIntOrNull() ?: 0, it.protocol) },
            publishAll = publishAll,
            // A tmpfs row keeps its old source in the draft (switching back restores it) but has none.
            mounts = mounts.map { if (it.type == MountType.Tmpfs && it !in initial.mounts) it.copy(source = "") else it },
            primaryNetwork = networkRows.firstOrNull(),
            extraNetworks = networkRows.drop(1),
            networkModeReadOnly = networkModeReadOnly,
            env = env.filter { envKey(it).isNotBlank() || envValue(it).isNotEmpty() },
            labels = labels.filter { (k, v) -> k.isNotBlank() || v.isNotEmpty() },
            restart = RestartPolicyForm(
                restartName,
                if (maxRetries == initial.maxRetries) before.restart.maxRetries else parseRetries(maxRetries) ?: before.restart.maxRetries,
            ),
            memoryBytes = typed(memory, initial.memory, before.memoryBytes, ::parseMemory),
            memoryReservationBytes = typed(memoryReservation, initial.memoryReservation, before.memoryReservationBytes, ::parseMemory),
            nanoCpus = typed(cpus, initial.cpus, before.nanoCpus, ::parseCpus),
            cpuShares = typed(cpuShares, initial.cpuShares, before.cpuShares, ::parseShares),
        )
    }

    companion object {
        fun of(form: ContainerForm) = EditorDraft(
            image = form.image,
            name = form.name,
            command = form.command?.let(::joinCommandLine).orEmpty(),
            entrypoint = form.entrypoint?.let(::joinCommandLine).orEmpty(),
            workingDir = form.workingDir,
            user = form.user,
            ports = form.ports.map { PortDraft(it.hostIp, it.hostPort, it.containerPort.toString(), it.protocol) },
            publishAll = form.publishAll,
            mounts = form.mounts,
            networks = (listOfNotNull(form.primaryNetwork) + form.extraNetworks).map(::networkDraft),
            networkModeReadOnly = form.networkModeReadOnly,
            env = form.env,
            labels = form.labels,
            restartName = form.restart.name,
            maxRetries = form.restart.maxRetries.toString(),
            memory = form.memoryBytes?.let(::formatMemory).orEmpty(),
            memoryReservation = form.memoryReservationBytes?.let(::formatMemory).orEmpty(),
            cpus = form.nanoCpus?.let(::formatCpus).orEmpty(),
            cpuShares = form.cpuShares?.toString().orEmpty(),
        )

        fun networkDraft(row: NetworkRow) = NetworkDraft(row.name, row.aliases.joinToString(", "), row.ipv4, row.ipv6, row.mac)
    }
}

/** Blank = image default (`null`). */
private fun args(text: String, initial: String, before: List<String>?): List<String>? = when {
    text == initial -> before
    text.isBlank() -> null
    else -> splitCommandLine(text).takeIf { it != splitCommandLine(initial) } ?: before
}

/** Blank = not set (`null`); zero counts as not set too (Docker's "unlimited"). */
private fun typed(text: String, initial: String, before: Long?, parse: (String) -> Long?): Long? = when {
    text == initial -> before
    text.isBlank() -> null
    else -> when (val value = parse(text)) {
        null -> before
        0L -> null
        else -> value
    }
}

internal fun splitAliases(text: String): List<String> = text.split(',', ' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/** The variable name of a `K=V` entry (the whole entry when it has no `=`). */
internal fun envKey(entry: String): String = entry.substringBefore('=')
internal fun envValue(entry: String): String = entry.substringAfter('=', "")

internal fun isHostOrNone(network: String) = network == "host" || network == "none"
