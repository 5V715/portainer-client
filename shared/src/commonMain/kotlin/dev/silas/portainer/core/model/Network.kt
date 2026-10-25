package dev.silas.portainer.core.model

private val PREDEFINED_NETWORKS = setOf("bridge", "host", "none")

/** One IPAM pool of a network. */
data class IpamSubnet(val subnet: String, val gateway: String?, val ipRange: String?)

/** A container attached to a network; [ipv4] is kept as Docker gives it (e.g. `172.18.0.2/16`). */
data class NetworkMember(val name: String, val ipv4: String?, val ipv6: String?)

/** A Docker network. [containers] is only filled by an inspect; the list endpoint returns it empty. */
data class Network(
    val id: String,
    val name: String,
    val driver: String,
    val scope: String,
    val internal: Boolean,
    val attachable: Boolean,
    val subnets: List<IpamSubnet>,
    val labels: Map<String, String>,
    val containers: Map<String, NetworkMember>,
) {
    /** Docker's built-in networks, which cannot be removed. */
    val predefined: Boolean get() = name in PREDEFINED_NETWORKS
}
