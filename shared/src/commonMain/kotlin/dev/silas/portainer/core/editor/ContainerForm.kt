package dev.silas.portainer.core.editor

/**
 * Typed values of the Edit / Duplicate / New container form. Built from inspect by [FormParser];
 * written back into the original inspect JSON by [FormMerger], which only touches sections that changed.
 */
data class ContainerForm(
    /** `Config.Image`. */
    val image: String,
    /** Container name without Docker's leading `/`. */
    val name: String,
    /** `Config.Cmd`; `null` = not set (image default). */
    val command: List<String>?,
    /** `Config.Entrypoint`; `null` = not set (image default). */
    val entrypoint: List<String>?,
    val workingDir: String,
    val user: String,
    /** One row per `HostConfig.PortBindings` binding. */
    val ports: List<PortRow>,
    /** `HostConfig.PublishAllPorts`. */
    val publishAll: Boolean,
    /** `HostConfig.Binds` rows, then `HostConfig.Mounts` rows (bind / volume / tmpfs only), then new rows. */
    val mounts: List<MountRow>,
    /** Network named by `HostConfig.NetworkMode`; `null` = Docker default (new form) or [networkModeReadOnly] set. */
    val primaryNetwork: NetworkRow?,
    val extraNetworks: List<NetworkRow>,
    /** `container:<id>`, `host` or `none`: shown read-only, networks are not editable. */
    val networkModeReadOnly: String?,
    /** `Config.Env` entries (`KEY=value`). */
    val env: List<String>,
    val labels: List<Pair<String, String>>,
    val restart: RestartPolicyForm,
    /** `HostConfig.Memory` in bytes; `null` = unlimited. */
    val memoryBytes: Long?,
    /** `HostConfig.MemoryReservation` in bytes; `null` = none. */
    val memoryReservationBytes: Long?,
    /** CPU limit in 1e-9 CPUs (`HostConfig.NanoCpus`); `null` = unlimited. */
    val nanoCpus: Long?,
    /** `HostConfig.CpuShares`; `null` = default. */
    val cpuShares: Long?,
)

/** A published port. Empty [hostIp] = all interfaces; empty [hostPort] = random port. */
data class PortRow(val hostIp: String, val hostPort: String, val containerPort: Int, val protocol: String /* tcp|udp */)

enum class MountType { Bind, Volume, Tmpfs }

/** Where a mount row came from, so an edited row is written back in the same representation. */
enum class MountOrigin { Binds, Mounts, New }

/**
 * A bind mount, named volume (empty [source] = anonymous volume) or tmpfs.
 * [originalIndex] is the row's index in `HostConfig.Binds` / `HostConfig.Mounts`; `null` for new rows.
 */
data class MountRow(
    val type: MountType,
    val source: String,
    val target: String,
    val readOnly: Boolean,
    val origin: MountOrigin,
    val originalIndex: Int?,
)

/** A network attachment; empty strings = not set. [aliases] leaves out Docker's generated short-id alias. */
data class NetworkRow(val name: String, val aliases: List<String>, val ipv4: String, val ipv6: String, val mac: String)

data class RestartPolicyForm(val name: String /* no|always|on-failure|unless-stopped */, val maxRetries: Int)

/** Form sections; [FormMerger] reports which ones the user edited. */
enum class FormField { Image, Name, Command, Entrypoint, WorkingDir, User, Ports, Mounts, Networks, Env, Labels, Restart, Resources }

/** Label keys that occur more than once; the merge keeps the last value, so the UI should flag these. */
fun duplicateLabelKeys(labels: List<Pair<String, String>>): Set<String> =
    labels.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
