package dev.silas.portainer.core.editor

private const val KIB = 1024L
private const val MIB = KIB * 1024
private const val GIB = MIB * 1024
private const val NANOS_PER_CPU = 1_000_000_000L

/**
 * Human-readable lines for every edited section except image and env (the change summary lists those itself),
 * e.g. `Ports: added 8080→80/tcp`, `Restart policy: always → unless-stopped`. Empty when nothing changed.
 */
fun describeChanges(before: ContainerForm, after: ContainerForm): List<String> = buildList {
    fun change(label: String, old: String, new: String) {
        if (old != new) add("$label: $old → $new")
    }
    change("Name", before.name, after.name)
    change("Command", describeArgs(before.command), describeArgs(after.command))
    change("Entrypoint", describeArgs(before.entrypoint), describeArgs(after.entrypoint))
    change("Working directory", before.workingDir.ifEmpty { "(image default)" }, after.workingDir.ifEmpty { "(image default)" })
    change("User", before.user.ifEmpty { "(image default)" }, after.user.ifEmpty { "(image default)" })

    if (before.publishAll != after.publishAll) add("Ports: publish all exposed ports ${if (after.publishAll) "on" else "off"}")
    multisetMinus(before.ports, after.ports).forEach { add("Ports: removed ${describePort(it)}") }
    multisetMinus(after.ports, before.ports).forEach { add("Ports: added ${describePort(it)}") }

    addAll(describeMounts(before.mounts, after.mounts))
    addAll(describeNetworks(before, after))

    val oldLabels = before.labels.toMap()
    val newLabels = after.labels.toMap()
    oldLabels.keys.filter { it !in newLabels }.forEach { add("Labels: removed $it") }
    newLabels.forEach { (k, v) ->
        val old = oldLabels[k]
        when {
            k !in oldLabels -> add("Labels: added $k=$v")
            old != v -> add("Labels: changed $k: $old → $v")
        }
    }

    change("Restart policy", describeRestart(before.restart), describeRestart(after.restart))
    change("Memory limit", describeBytes(before.memoryBytes, "unlimited"), describeBytes(after.memoryBytes, "unlimited"))
    change("Memory reservation", describeBytes(before.memoryReservationBytes, "none"), describeBytes(after.memoryReservationBytes, "none"))
    change("CPU limit", describeCpus(before.nanoCpus), describeCpus(after.nanoCpus))
    change("CPU shares", before.cpuShares?.toString() ?: "default", after.cpuShares?.toString() ?: "default")
}

private fun describeArgs(args: List<String>?) = when {
    args == null -> "(image default)"
    args.isEmpty() -> "(empty)"
    else -> joinCommandLine(args)
}

/** [a] minus [b], removing one occurrence per element of [b]. */
private fun <T> multisetMinus(a: List<T>, b: List<T>): List<T> {
    val rest = b.toMutableList()
    return a.filter { !rest.remove(it) }
}

private fun describePort(row: PortRow): String {
    val host = row.hostPort.ifEmpty { "random" }
    val prefix = if (row.hostIp.isEmpty()) host else "${row.hostIp}:$host"
    return "$prefix→${row.containerPort}/${row.protocol}"
}

/** Existing rows are matched by where they came from, so an edited row reads as one change. */
private fun describeMounts(before: List<MountRow>, after: List<MountRow>): List<String> = buildList {
    fun key(row: MountRow) = if (row.origin == MountOrigin.New) null else row.origin to row.originalIndex
    val afterByKey = after.mapNotNull { row -> key(row)?.let { it to row } }.toMap()
    val beforeByKey = before.mapNotNull { row -> key(row)?.let { it to row } }.toMap()
    for (row in before) {
        val now = key(row)?.let(afterByKey::get)
        when {
            now == null -> add("Mounts: removed ${describeMount(row)}")
            now != row -> add("Mounts: changed ${describeMount(row)} to ${describeMount(now)}")
        }
    }
    after.filter { row -> key(row)?.let(beforeByKey::get) == null }.forEach { add("Mounts: added ${describeMount(it)}") }
}

private fun describeMount(row: MountRow): String {
    val source = when (row.type) {
        MountType.Bind -> "bind ${row.source}"
        MountType.Volume -> if (row.source.isEmpty()) "anonymous volume" else "volume ${row.source}"
        MountType.Tmpfs -> "tmpfs"
    }
    return "$source → ${row.target}" + if (row.readOnly) " (read-only)" else ""
}

private fun describeNetworks(before: ContainerForm, after: ContainerForm): List<String> = buildList {
    if (before.networkModeReadOnly != after.networkModeReadOnly) {
        add("Network mode: ${before.networkModeReadOnly ?: "networks"} → ${after.networkModeReadOnly ?: "networks"}")
    }
    if (before.primaryNetwork?.name != after.primaryNetwork?.name) {
        add("Networks: primary ${before.primaryNetwork?.name ?: "(none)"} → ${after.primaryNetwork?.name ?: "(none)"}")
    }
    val old = (listOfNotNull(before.primaryNetwork) + before.extraNetworks).associateBy { it.name }
    val new = (listOfNotNull(after.primaryNetwork) + after.extraNetworks).associateBy { it.name }
    old.keys.filter { it !in new }.forEach { add("Networks: removed $it") }
    new.keys.filter { it !in old }.forEach { add("Networks: added $it") }
    new.forEach { (name, row) -> if (name in old && old[name] != row) add("Networks: changed settings of $name") }
}

private fun describeRestart(policy: RestartPolicyForm) =
    if (policy.name == "on-failure" && policy.maxRetries > 0) "on-failure (max ${policy.maxRetries} retries)" else policy.name

private fun describeBytes(bytes: Long?, none: String): String = when {
    bytes == null -> none
    bytes % GIB == 0L -> "${bytes / GIB} GiB"
    bytes % MIB == 0L -> "${bytes / MIB} MiB"
    bytes % KIB == 0L -> "${bytes / KIB} KiB"
    else -> "$bytes bytes"
}

private fun describeCpus(nanos: Long?): String {
    if (nanos == null) return "unlimited"
    val whole = nanos / NANOS_PER_CPU
    val fraction = (nanos % NANOS_PER_CPU).toString().padStart(9, '0').trimEnd('0')
    val value = if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
    return if (nanos == NANOS_PER_CPU) "1 CPU" else "$value CPUs"
}
