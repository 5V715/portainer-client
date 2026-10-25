package dev.silas.portainer.core.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Per-type option objects of a `HostConfig.Mounts` entry; the others are dropped when a row changes type. */
private val MOUNT_OPTION_KEYS = mapOf("bind" to "BindOptions", "volume" to "VolumeOptions", "tmpfs" to "TmpfsOptions")

private val EMPTY = JsonObject(emptyMap())

private const val DEFAULT_NETWORK = "bridge"

/**
 * Writes form edits back into inspect-shaped JSON. Only sections whose form values differ between `before`
 * and `after` are rewritten, and within them only the entries that changed; everything else (including
 * every field the form doesn't cover) stays exactly as in the original JSON.
 */
object FormMerger {
    /**
     * @param before the form as parsed from [original] (`FormParser.fromInspect(original)`).
     * @param after the form as the user left it.
     * @return the edited inspect JSON and the sections the user changed.
     */
    fun merge(original: JsonObject, before: ContainerForm, after: ContainerForm): Pair<JsonObject, Set<FormField>> {
        val fields = changedFields(before, after)
        if (fields.isEmpty()) return original to emptySet()

        val root = original.toMutableMap()
        val config = original.obj("Config").orEmpty().toMutableMap()
        val host = original.obj("HostConfig").orEmpty().toMutableMap()

        for (field in fields) {
            when (field) {
                FormField.Image -> config["Image"] = JsonPrimitive(after.image)
                FormField.Name -> root["Name"] = JsonPrimitive("/" + after.name)
                FormField.Command -> config["Cmd"] = after.command?.let(::stringArray) ?: JsonNull
                FormField.Entrypoint -> config["Entrypoint"] = after.entrypoint?.let(::stringArray) ?: JsonNull
                FormField.WorkingDir -> config["WorkingDir"] = JsonPrimitive(after.workingDir)
                FormField.User -> config["User"] = JsonPrimitive(after.user)
                FormField.Env -> config.putOrRemove("Env", after.env.takeIf { it.isNotEmpty() }?.let(::stringArray))
                FormField.Labels -> config.putOrRemove(
                    "Labels",
                    after.labels.takeIf { it.isNotEmpty() }?.let { labels -> JsonObject(labels.associate { (k, v) -> k to JsonPrimitive(v) }) },
                )
                FormField.Ports -> mergePorts(config, host, before, after)
                FormField.Mounts -> mergeMounts(host, before, after)
                FormField.Networks -> mergeNetworks(root, host, before, after)
                FormField.Restart -> {
                    val policy = host.obj("RestartPolicy").orEmpty().toMutableMap()
                    policy["Name"] = JsonPrimitive(after.restart.name)
                    // Docker only accepts a retry count with on-failure.
                    policy["MaximumRetryCount"] = JsonPrimitive(if (after.restart.name == "on-failure") after.restart.maxRetries else 0)
                    host["RestartPolicy"] = JsonObject(policy)
                }
                FormField.Resources -> mergeResources(host, before, after)
            }
        }

        root.putIfChanged("Config", JsonObject(config), original.obj("Config"))
        root.putIfChanged("HostConfig", JsonObject(host), original.obj("HostConfig"))
        return JsonObject(root) to fields
    }

    private fun changedFields(before: ContainerForm, after: ContainerForm): Set<FormField> = buildSet {
        if (before.image != after.image) add(FormField.Image)
        if (before.name != after.name) add(FormField.Name)
        if (before.command != after.command) add(FormField.Command)
        if (before.entrypoint != after.entrypoint) add(FormField.Entrypoint)
        if (before.workingDir != after.workingDir) add(FormField.WorkingDir)
        if (before.user != after.user) add(FormField.User)
        if (before.ports != after.ports || before.publishAll != after.publishAll) add(FormField.Ports)
        if (before.mounts != after.mounts) add(FormField.Mounts)
        if (before.primaryNetwork != after.primaryNetwork || before.extraNetworks != after.extraNetworks ||
            before.networkModeReadOnly != after.networkModeReadOnly
        ) {
            add(FormField.Networks)
        }
        if (before.env != after.env) add(FormField.Env)
        if (before.labels != after.labels) add(FormField.Labels)
        if (before.restart != after.restart) add(FormField.Restart)
        if (before.memoryBytes != after.memoryBytes || before.memoryReservationBytes != after.memoryReservationBytes ||
            before.nanoCpus != after.nanoCpus || before.cpuShares != after.cpuShares
        ) {
            add(FormField.Resources)
        }
    }

    // ---- ports ----

    /**
     * Rewrites `HostConfig.PortBindings` per `port/proto` key (unchanged keys keep their original JSON) and
     * keeps `Config.ExposedPorts` in step: keys whose last binding was removed go, new keys are added,
     * exposed ports that never had a binding (image `EXPOSE`, `--expose`) stay.
     */
    private fun mergePorts(config: MutableMap<String, JsonElement>, host: MutableMap<String, JsonElement>, before: ContainerForm, after: ContainerForm) {
        if (before.publishAll != after.publishAll) host["PublishAllPorts"] = JsonPrimitive(after.publishAll)
        if (before.ports == after.ports) return

        val originalBindings = host.obj("PortBindings") ?: EMPTY
        val beforeByKey = before.ports.groupBy(::portKey)
        val afterByKey = after.ports.groupBy(::portKey)
        val bindings = linkedMapOf<String, JsonElement>()
        originalBindings.forEach { (key, value) -> if (FormParser.parsePortKey(key) == null) bindings[key] = value }
        for ((key, rows) in afterByKey) {
            val kept = originalBindings[key]?.takeIf { rows == beforeByKey[key] }
            bindings[key] = kept ?: JsonArray(
                rows.map { JsonObject(mapOf("HostIp" to JsonPrimitive(it.hostIp), "HostPort" to JsonPrimitive(it.hostPort))) },
            )
        }
        host.putIfChanged("PortBindings", JsonObject(bindings).takeIf { it.isNotEmpty() }, originalBindings)

        val originalExposed = config.obj("ExposedPorts") ?: EMPTY
        val exposed = originalExposed.toMutableMap()
        (beforeByKey.keys - afterByKey.keys).forEach { exposed.remove(it) }
        afterByKey.keys.forEach { if (it !in exposed) exposed[it] = EMPTY }
        config.putIfChanged("ExposedPorts", JsonObject(exposed).takeIf { it.isNotEmpty() }, originalExposed)
    }

    private fun portKey(row: PortRow) = "${row.containerPort}/${row.protocol}"

    // ---- mounts ----

    /**
     * Rows go back to where they came from: `Binds` rows as `-v` strings (keeping their other mode words),
     * `Mounts` rows as objects (keeping their other keys). New bind / named-volume rows become `Binds`
     * strings (`-v` semantics: Docker creates a missing host path), tmpfs rows `Mounts` objects.
     * Unchanged rows keep their original JSON; a `Binds` string that [isEditableInPlace] rejects is kept
     * verbatim even when edited; `Mounts` entries of types the form doesn't cover keep their positions.
     */
    private fun mergeMounts(host: MutableMap<String, JsonElement>, before: ContainerForm, after: ContainerForm) {
        val originalBinds = host.array("Binds") ?: JsonArray(emptyList())
        val originalMounts = host.array("Mounts") ?: JsonArray(emptyList())
        val beforeRows = before.mounts.filter { it.origin != MountOrigin.New }.associateBy { it.origin to it.originalIndex }

        val binds = mutableListOf<JsonElement>()
        val mounts = mutableListOf<JsonElement>()
        for (row in after.mounts) {
            val prior = if (row.origin == MountOrigin.New) null else beforeRows[row.origin to row.originalIndex]
            val asBind = row.type != MountType.Tmpfs && (row.origin == MountOrigin.Binds || row.origin == MountOrigin.New)
            if (asBind) {
                val orig = if (row.origin == MountOrigin.Binds) row.originalIndex?.let { originalBinds.getOrNull(it) } else null
                val origText = (orig as? JsonPrimitive)?.takeIf { it.isString }?.content
                val origSpec = origText?.let(::parseBind)
                val keepVerbatim = orig != null && (prior == row || prior == null || bindString(prior, origSpec) != origText)
                binds += if (keepVerbatim) orig else JsonPrimitive(bindString(row, origSpec))
            } else {
                val orig = if (row.origin == MountOrigin.Mounts) row.originalIndex?.let { originalMounts.getOrNull(it) as? JsonObject } else null
                mounts += if (prior == row && orig != null) orig else mountObject(row, orig)
            }
        }
        originalMounts.forEachIndexed { i, m ->
            if (m !is JsonObject || FormParser.mountRow(m, i) == null) mounts.add(minOf(i, mounts.size), m)
        }
        host.putIfChanged("Binds", JsonArray(binds).takeIf { it.isNotEmpty() }, originalBinds)
        host.putIfChanged("Mounts", JsonArray(mounts).takeIf { it.isNotEmpty() }, originalMounts)
    }

    /**
     * Whether an edit of [row] (as parsed from [original]) can be written back without losing anything: false for a `Binds` string this
     * app's `-v` grammar doesn't reproduce exactly (the merge then keeps it verbatim; the UI should lock the row).
     */
    fun isEditableInPlace(original: JsonObject, row: MountRow): Boolean {
        if (row.origin != MountOrigin.Binds) return true
        val text = row.originalIndex?.let { original.obj("HostConfig")?.array("Binds")?.getOrNull(it) as? JsonPrimitive }?.content
            ?: return true
        return bindString(row, parseBind(text)) == text
    }

    /** `[src:]dst[:opts]`; the `ro`/`rw` word is set from the row, the original's other words are kept. */
    internal fun bindString(row: MountRow, original: BindSpec?): String {
        val options = original?.options.orEmpty().toMutableList()
        val flagIndex = options.indexOfFirst { it == "ro" || it == "rw" }
        if (flagIndex >= 0) {
            options[flagIndex] = if (row.readOnly) "ro" else "rw"
        } else if (row.readOnly) {
            options.add(0, "ro")
        }
        val spec = if (row.source.isEmpty()) row.target else "${row.source}:${row.target}"
        return if (options.isEmpty()) spec else spec + ":" + options.joinToString(",")
    }

    internal fun mountObject(row: MountRow, original: JsonObject?): JsonObject {
        val type = when (row.type) {
            MountType.Bind -> "bind"
            MountType.Volume -> "volume"
            MountType.Tmpfs -> "tmpfs"
        }
        val out = original.orEmpty().toMutableMap()
        if (original?.string("Type") != type) MOUNT_OPTION_KEYS.forEach { (t, key) -> if (t != type) out.remove(key) }
        out["Type"] = JsonPrimitive(type)
        out.putOrRemove("Source", row.source.takeIf { it.isNotEmpty() && row.type != MountType.Tmpfs }?.let(::JsonPrimitive))
        out["Target"] = JsonPrimitive(row.target)
        if (row.readOnly || "ReadOnly" in out) out["ReadOnly"] = JsonPrimitive(row.readOnly)
        return JsonObject(out)
    }

    // ---- networks ----

    /**
     * Rewrites `NetworkSettings.Networks` per network (unchanged endpoints keep their JSON, removed networks
     * go) and points `HostConfig.NetworkMode` at a new primary network, or at `bridge` when none is left. A read-only mode (container:/host/none)
     * is only written if the form itself changes it.
     */
    private fun mergeNetworks(root: MutableMap<String, JsonElement>, host: MutableMap<String, JsonElement>, before: ContainerForm, after: ContainerForm) {
        val settings = root.obj("NetworkSettings")
        val originalNetworks = settings?.obj("Networks") ?: EMPTY
        val readOnlyMode = after.networkModeReadOnly
        val networks: JsonObject
        if (readOnlyMode != null) {
            if (readOnlyMode == before.networkModeReadOnly) return
            host["NetworkMode"] = JsonPrimitive(readOnlyMode)
            networks = EMPTY
        } else {
            val beforeByName = (listOfNotNull(before.primaryNetwork) + before.extraNetworks).associateBy { it.name }
            val out = linkedMapOf<String, JsonElement>()
            // Removing the primary network while others remain promotes the first remaining one.
            val primaryRow = after.primaryNetwork ?: after.extraNetworks.firstOrNull()
            for (row in listOfNotNull(after.primaryNetwork) + after.extraNetworks) {
                val orig = originalNetworks[row.name] as? JsonObject
                val prior = beforeByName[row.name]
                out[row.name] = if (orig != null && prior == row) orig else endpoint(row, orig, prior)
            }
            networks = JsonObject(out)
            val primary = primaryRow?.name
            if (primary != null && (primary != before.primaryNetwork?.name || before.networkModeReadOnly != null)) {
                host["NetworkMode"] = JsonPrimitive(primary)
            } else if (primary == null && (before.primaryNetwork != null || before.networkModeReadOnly != null)) {
                // No network left: NetworkMode would still name the removed one, so use Docker's default bridge.
                host["NetworkMode"] = JsonPrimitive(DEFAULT_NETWORK)
            }
        }
        if (networks != originalNetworks) {
            root["NetworkSettings"] = JsonObject(settings.orEmpty() + ("Networks" to networks))
        }
    }

    /** [original] endpoint with the fields that differ from [prior] (all fields for a new network) replaced. */
    private fun endpoint(row: NetworkRow, original: JsonObject?, prior: NetworkRow?): JsonObject {
        val out = original.orEmpty().toMutableMap()
        if (prior?.aliases != row.aliases) out.putOrRemove("Aliases", row.aliases.takeIf { it.isNotEmpty() }?.let(::stringArray))
        if (prior?.ipv4 != row.ipv4 || prior.ipv6 != row.ipv6) {
            val ipam = out.obj("IPAMConfig").orEmpty().toMutableMap()
            ipam.putOrRemove("IPv4Address", row.ipv4.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
            ipam.putOrRemove("IPv6Address", row.ipv6.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
            when {
                ipam.isNotEmpty() -> out["IPAMConfig"] = JsonObject(ipam)
                "IPAMConfig" in out -> out["IPAMConfig"] = JsonNull
            }
        }
        if (prior?.mac != row.mac) out.putOrRemove("MacAddress", row.mac.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
        return JsonObject(out)
    }

    // ---- resources ----

    /** Cleared values are written as 0 (Docker's "unlimited"). */
    private fun mergeResources(host: MutableMap<String, JsonElement>, before: ContainerForm, after: ContainerForm) {
        if (before.memoryBytes != after.memoryBytes) {
            val memory = after.memoryBytes ?: 0L
            host["Memory"] = JsonPrimitive(memory)
            // Docker rejects a swap limit without a memory limit or below it; 0 lets it default to 2 × memory.
            val swap = host.long("MemorySwap")
            if (swap != null && swap > 0 && (memory == 0L || memory > swap)) host["MemorySwap"] = JsonPrimitive(0L)
        }
        if (before.memoryReservationBytes != after.memoryReservationBytes) {
            host["MemoryReservation"] = JsonPrimitive(after.memoryReservationBytes ?: 0L)
        }
        if (before.cpuShares != after.cpuShares) host["CpuShares"] = JsonPrimitive(after.cpuShares ?: 0L)
        if (before.nanoCpus != after.nanoCpus) {
            host["NanoCpus"] = JsonPrimitive(after.nanoCpus ?: 0L)
            // Docker rejects NanoCpus next to CpuQuota/CpuPeriod; the form's CPU limit replaces them.
            for (key in listOf("CpuQuota", "CpuPeriod")) {
                if ((host.long(key) ?: 0L) > 0L) host[key] = JsonPrimitive(0L)
            }
        }
    }

    private fun MutableMap<String, JsonElement>.putOrRemove(key: String, value: JsonElement?) {
        if (value == null) remove(key) else put(key, value)
    }

    /** Sets [key] to [value] (removes it for `null`) unless that equals [original], so untouched JSON stays as it was. */
    private fun MutableMap<String, JsonElement>.putIfChanged(key: String, value: JsonElement?, original: JsonElement?) {
        if (value == original || (isEmptyJson(value) && isEmptyJson(original))) return
        putOrRemove(key, value)
    }

    private fun isEmptyJson(value: JsonElement?) = when (value) {
        null, JsonNull -> true
        is JsonObject -> value.isEmpty()
        is JsonArray -> value.isEmpty()
        else -> false
    }
}
