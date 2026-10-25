package dev.silas.portainer.core.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val NANOS_PER_CPU = 1_000_000_000L

/** Builds a [ContainerForm] from `GET /containers/{id}/json` output, or an empty one for a new container. */
object FormParser {
    fun fromInspect(inspect: JsonObject): ContainerForm {
        val config = inspect.obj("Config") ?: JsonObject(emptyMap())
        val host = inspect.obj("HostConfig") ?: JsonObject(emptyMap())
        val networkMode = host.string("NetworkMode").orEmpty()
        val readOnlyMode = networkMode.takeIf(::isReadOnlyNetworkMode)
        val shortId = inspect.string("Id").orEmpty().take(SHORT_ID_LENGTH)
        val networks = if (readOnlyMode == null) networkRows(networkMode, inspect.obj("NetworkSettings")?.obj("Networks"), shortId) else null

        return ContainerForm(
            image = config.string("Image").orEmpty(),
            name = inspect.string("Name").orEmpty().removePrefix("/"),
            command = stringListOrNull(config["Cmd"]),
            entrypoint = stringListOrNull(config["Entrypoint"]),
            workingDir = config.string("WorkingDir").orEmpty(),
            user = config.string("User").orEmpty(),
            ports = portRows(host.obj("PortBindings")),
            publishAll = host.boolean("PublishAllPorts"),
            mounts = mountRows(host),
            primaryNetwork = networks?.firstOrNull(),
            extraNetworks = networks?.drop(1).orEmpty(),
            networkModeReadOnly = readOnlyMode,
            env = config.array("Env").strings(),
            labels = config.obj("Labels").orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } },
            restart = RestartPolicyForm(
                name = host.obj("RestartPolicy")?.string("Name").orEmpty().ifEmpty { "no" },
                maxRetries = host.obj("RestartPolicy")?.long("MaximumRetryCount")?.toInt() ?: 0,
            ),
            memoryBytes = host.positive("Memory"),
            memoryReservationBytes = host.positive("MemoryReservation"),
            nanoCpus = cpuLimit(host),
            cpuShares = host.positive("CpuShares"),
        )
    }

    fun empty() = ContainerForm(
        image = "", name = "", command = null, entrypoint = null, workingDir = "", user = "",
        ports = emptyList(), publishAll = false, mounts = emptyList(),
        primaryNetwork = null, extraNetworks = emptyList(), networkModeReadOnly = null,
        env = emptyList(), labels = emptyList(), restart = RestartPolicyForm("no", 0),
        memoryBytes = null, memoryReservationBytes = null, nanoCpus = null, cpuShares = null,
    )

    /** One row per binding; a port key with no bindings gives one row with empty host fields. */
    internal fun portRows(bindings: JsonObject?): List<PortRow> = bindings.orEmpty().flatMap { (key, value) ->
        val (port, protocol) = parsePortKey(key) ?: return@flatMap emptyList()
        val list = (value as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (list.isEmpty()) {
            listOf(PortRow("", "", port, protocol))
        } else {
            list.map { PortRow(it.string("HostIp").orEmpty(), it.string("HostPort").orEmpty(), port, protocol) }
        }
    }

    /** `80/tcp` → (80, "tcp"); a key without protocol is tcp. */
    internal fun parsePortKey(key: String): Pair<Int, String>? {
        val port = key.substringBefore('/').toIntOrNull() ?: return null
        return port to (key.substringAfter('/', "tcp").ifEmpty { "tcp" })
    }

    internal fun mountRows(host: JsonObject): List<MountRow> {
        val binds = host.array("Binds").strings().mapIndexed { i, bind -> bindRow(bind, i) }
        val mounts = host.array("Mounts").orEmpty().mapIndexedNotNull { i, m -> (m as? JsonObject)?.let { mountRow(it, i) } }
        return binds + mounts
    }

    internal fun bindRow(bind: String, index: Int): MountRow {
        val spec = parseBind(bind)
        val type = if (spec.source?.startsWith("/") == true) MountType.Bind else MountType.Volume
        return MountRow(type, spec.source.orEmpty(), spec.target, readOnly = "ro" in spec.options, MountOrigin.Binds, index)
    }

    /** `null` for mount types the form doesn't cover (npipe, cluster, image): those are kept untouched. */
    internal fun mountRow(mount: JsonObject, index: Int): MountRow? {
        val type = mountType(mount.string("Type")) ?: return null
        return MountRow(type, mount.string("Source").orEmpty(), mount.string("Target").orEmpty(), mount.boolean("ReadOnly"), MountOrigin.Mounts, index)
    }

    internal fun mountType(name: String?) = when (name) {
        "bind" -> MountType.Bind
        "volume" -> MountType.Volume
        "tmpfs" -> MountType.Tmpfs
        else -> null
    }

    /**
     * Primary first: the network named by [networkMode] (`default` = `bridge`) or matching its NetworkID,
     * else the first attached one, else a bare row for [networkMode] itself.
     */
    private fun networkRows(networkMode: String, networks: JsonObject?, shortId: String): List<NetworkRow> {
        val rows = networks.orEmpty().map { (name, endpoint) -> networkRow(name, endpoint as? JsonObject, shortId) }
        val wanted = if (networkMode == "default") "bridge" else networkMode
        val primaryIndex = networks.orEmpty().entries.indexOfFirst { (name, endpoint) ->
            name == wanted || (endpoint as? JsonObject)?.string("NetworkID") == wanted
        }
        return when {
            primaryIndex >= 0 -> listOf(rows[primaryIndex]) + rows.filterIndexed { i, _ -> i != primaryIndex }
            rows.isNotEmpty() -> rows
            wanted.isNotEmpty() -> listOf(NetworkRow(wanted, emptyList(), "", "", ""))
            else -> emptyList()
        }
    }

    internal fun networkRow(name: String, endpoint: JsonObject?, shortId: String): NetworkRow {
        val ipam = endpoint?.obj("IPAMConfig")
        return NetworkRow(
            name = name,
            aliases = endpoint?.array("Aliases").strings().filter { it != shortId || shortId.isEmpty() },
            ipv4 = ipam?.string("IPv4Address").orEmpty(),
            ipv6 = ipam?.string("IPv6Address").orEmpty(),
            mac = endpoint?.string("MacAddress").orEmpty(),
        )
    }

    /** `NanoCpus`, or the equivalent of `CpuQuota`/`CpuPeriod` (`--cpu-quota`) when only those are set. */
    private fun cpuLimit(host: JsonObject): Long? {
        host.positive("NanoCpus")?.let { return it }
        val quota = host.positive("CpuQuota") ?: return null
        val period = host.positive("CpuPeriod") ?: 100_000L
        return quota * NANOS_PER_CPU / period
    }

    private fun Map<String, JsonElement>.positive(key: String) = long(key)?.takeIf { it > 0 }

    private fun stringListOrNull(value: JsonElement?): List<String>? = when (value) {
        null, JsonNull -> null
        is JsonArray -> value.strings()
        else -> null
    }
}
