package dev.silas.portainer.core.editor

import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.model.PublishedPort
import dev.silas.portainer.core.resources.ResourceUsage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Something that would go wrong, or surprise the user, when a copy / new container is deployed. */
sealed interface Conflict {
    data class NameTaken(val name: String) : Conflict

    data class HostPortInUse(val port: String, val protocol: String, val byContainer: String, val running: Boolean) : Conflict

    data class FixedIpInUse(val network: String, val ip: String, val byContainer: String) : Conflict

    data class SharedNamedVolume(val volume: String) : Conflict
}

/** Conflicts that must be resolved before deploying; the rest are warnings. */
val Conflict.blocking: Boolean
    get() = when (this) {
        is Conflict.NameTaken, is Conflict.FixedIpInUse -> true
        is Conflict.HostPortInUse -> running
        is Conflict.SharedNamedVolume -> false
    }

private const val MAX_PORT = 65535
private const val ANONYMOUS_VOLUME_LENGTH = 64
private val HEX_NAME = Regex("[0-9a-f]{$ANONYMOUS_VOLUME_LENGTH}")
private val ALL_INTERFACES = setOf("", "0.0.0.0", "::", "[::]")

private data class WantedPort(val ip: String, val port: Int, val protocol: String)

object ConflictChecker {
    /**
     * Checks a create [body] (Docker create-body shape) named [name] against the other containers and networks.
     * [stoppedPortBindings] holds the `HostConfig.PortBindings` of stopped containers by name (the container list
     * only reports published ports of running ones). [stoppedFixedIps] holds stopped containers' fixed IPs by name,
     * as (network name, IP) pairs from their inspect's `IPAMConfig` (a network inspect only lists running
     * endpoints, yet the IP is reserved for the stopped container: one of the two would fail to start).
     * [excludeContainerId] is the container a Replace overwrites.
     */
    fun check(
        body: JsonObject,
        name: String,
        others: List<ContainerSummary>,
        networks: List<Network>,
        excludeContainerId: String?,
        stoppedPortBindings: Map<String, List<PublishedPort>> = emptyMap(),
        stoppedFixedIps: Map<String, List<Pair<String, String>>> = emptyMap(),
    ): List<Conflict> {
        val peers = others.filter { it.id != excludeContainerId }
        val excludedName = others.firstOrNull { it.id == excludeContainerId }?.name?.removePrefix("/")
        val hostConfig = body.obj("HostConfig")
        return buildList {
            val wanted = name.removePrefix("/")
            if (peers.any { it.name.removePrefix("/") == wanted }) add(Conflict.NameTaken(wanted))
            addAll(portConflicts(hostConfig, peers, stoppedPortBindings.filterKeys { it.removePrefix("/") != excludedName }))
            addAll(ipConflicts(body, networks, excludeContainerId, stoppedFixedIps.filterKeys { it.removePrefix("/") != excludedName }))
            addAll(volumeConflicts(hostConfig, peers))
        }.distinct()
    }

    private fun portConflicts(
        hostConfig: JsonObject?,
        peers: List<ContainerSummary>,
        stopped: Map<String, List<PublishedPort>>,
    ): List<Conflict> {
        val wanted = wantedPorts(hostConfig)
        if (wanted.isEmpty()) return emptyList()
        val holders = peers.flatMap { c -> c.ports.map { Triple(c.name.removePrefix("/"), it, true) } } +
            stopped.flatMap { (n, ports) -> ports.map { Triple(n.removePrefix("/"), it, false) } }
        return wanted.flatMap { w ->
            holders.filter { (_, p, _) ->
                p.publicPort == w.port && p.type.lowercase() == w.protocol && ipsOverlap(w.ip, p.ip.orEmpty())
            }.map { (holder, _, running) -> Conflict.HostPortInUse(w.port.toString(), w.protocol, holder, running) }
        }
    }

    private fun ipsOverlap(a: String, b: String) = a in ALL_INTERFACES || b in ALL_INTERFACES || a == b

    private fun wantedPorts(hostConfig: JsonObject?): List<WantedPort> {
        val bindings = hostConfig?.obj("PortBindings") ?: return emptyList()
        return bindings.flatMap { (key, value) ->
            val protocol = key.substringAfter('/', "tcp").lowercase()
            (value as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.flatMap { binding ->
                val ip = binding.string("HostIp").orEmpty()
                expandPorts(binding.string("HostPort").orEmpty()).map { WantedPort(ip, it, protocol) }
            }
        }
    }

    /** `8080` -> [8080]; `8000-8010` -> 8000..8010; empty (Docker picks one) or malformed -> nothing. */
    private fun expandPorts(spec: String): List<Int> {
        val trimmed = spec.trim()
        val single = trimmed.toIntOrNull()
        if (single != null) return listOf(single)
        val start = trimmed.substringBefore('-').toIntOrNull()
        val end = trimmed.substringAfter('-', "").toIntOrNull()
        return if (start != null && end != null && start in 1..end && end <= MAX_PORT) (start..end).toList() else emptyList()
    }

    private fun ipConflicts(
        body: JsonObject,
        networks: List<Network>,
        excludeContainerId: String?,
        stopped: Map<String, List<Pair<String, String>>>,
    ): List<Conflict> {
        val endpoints = body.obj("NetworkingConfig")?.obj("EndpointsConfig") ?: return emptyList()
        return endpoints.flatMap { (networkName, endpoint) ->
            val ipam = (endpoint as? JsonObject)?.obj("IPAMConfig") ?: return@flatMap emptyList()
            val wanted = listOfNotNull(ipam.string("IPv4Address"), ipam.string("IPv6Address")).filter { it.isNotEmpty() }
            val members = networks.filter { it.name == networkName }.flatMap { it.containers.entries }
                .filter { it.key != excludeContainerId }
            wanted.flatMap { ip ->
                members.filter { (_, m) -> listOfNotNull(m.ipv4, m.ipv6).any { it.substringBefore('/') == ip } }
                    .map { (_, m) -> Conflict.FixedIpInUse(networkName, ip, m.name.removePrefix("/")) } +
                    stopped.filter { (_, ips) -> ips.any { (n, held) -> n == networkName && held.substringBefore('/') == ip } }
                        .map { (holder, _) -> Conflict.FixedIpInUse(networkName, ip, holder.removePrefix("/")) }
            }
        }
    }

    private fun volumeConflicts(hostConfig: JsonObject?, peers: List<ContainerSummary>): List<Conflict> {
        val usage = ResourceUsage(peers)
        return namedVolumes(hostConfig).filter { usage.isVolumeUsed(it) }.map { Conflict.SharedNamedVolume(it) }
    }

    private fun namedVolumes(hostConfig: JsonObject?): List<String> {
        val fromBinds = hostConfig?.array("Binds").strings().mapNotNull { bind ->
            bind.substringBefore(':').takeIf { it.isNotEmpty() && !isHostPath(bind) && bind.contains(':') }
        }
        val fromMounts = hostConfig?.array("Mounts").orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.string("Type") == "volume" }.mapNotNull { it.string("Source") }
        return (fromBinds + fromMounts).filter { it.isNotEmpty() && !HEX_NAME.matches(it) }.distinct()
    }
}
