package dev.silas.portainer.core.editor

import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.model.NetworkMember
import dev.silas.portainer.core.model.PublishedPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConflictCheckerTest {
    private fun body(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun c(
        id: String,
        name: String,
        state: ContainerState = ContainerState.Running,
        volumes: List<String> = emptyList(),
        ports: List<PublishedPort> = emptyList(),
    ) = ContainerSummary(id, name, "app:1", state, "", null, null, emptyMap(), "", volumes, emptyList(), ports)

    private fun portBody(key: String, hostIp: String, hostPort: String) =
        body("""{"HostConfig":{"PortBindings":{"$key":[{"HostIp":"$hostIp","HostPort":"$hostPort"}]}}}""")

    private fun check(
        body: JsonObject = body("{}"),
        name: String = "new",
        others: List<ContainerSummary> = emptyList(),
        networks: List<Network> = emptyList(),
        exclude: String? = null,
        stopped: Map<String, List<PublishedPort>> = emptyMap(),
        stoppedIps: Map<String, List<Pair<String, String>>> = emptyMap(),
    ) = ConflictChecker.check(body, name, others, networks, exclude, stopped, stoppedIps)

    private fun net(vararg members: Pair<String, NetworkMember>) =
        Network("n1", "backend", "bridge", "local", false, false, emptyList(), emptyMap(), members.toMap())

    @Test
    fun nameTakenBlocks() {
        val result = check(name = "web", others = listOf(c("1", "/web")))
        assertEquals(listOf(Conflict.NameTaken("web")), result)
        assertTrue(result.single().blocking)
    }

    @Test
    fun replaceExcludesItself() {
        val web = c("1", "web", volumes = listOf("db"), ports = listOf(PublishedPort("0.0.0.0", 8080, 80, "tcp")))
        val b = body("""{"HostConfig":{"Binds":["db:/data"],"PortBindings":{"80/tcp":[{"HostIp":"","HostPort":"8080"}]}}}""")
        assertEquals(emptyList(), check(b, "web", listOf(web), exclude = "1"))
        assertTrue(check(b, "web", listOf(web)).isNotEmpty())
    }

    @Test
    fun runningHolderOnAllInterfacesBlocksAnyIp() {
        val holder = c("1", "web", ports = listOf(PublishedPort("0.0.0.0", 8080, 80, "tcp")))
        val result = check(portBody("80/tcp", "192.168.1.5", "8080"), others = listOf(holder))
        assertEquals(listOf(Conflict.HostPortInUse("8080", "tcp", "web", true)), result)
        assertTrue(result.single().blocking)
    }

    @Test
    fun specificHolderVsAllInterfacesBody() {
        val holder = c("1", "web", ports = listOf(PublishedPort("10.0.0.1", 8080, 80, "tcp")))
        assertEquals(1, check(portBody("80/tcp", "", "8080"), others = listOf(holder)).size)
        assertEquals(1, check(portBody("80/tcp", "::", "8080"), others = listOf(holder)).size)
    }

    @Test
    fun differentSpecificIpsDoNotConflict() {
        val holder = c("1", "web", ports = listOf(PublishedPort("10.0.0.1", 8080, 80, "tcp")))
        assertEquals(emptyList(), check(portBody("80/tcp", "10.0.0.2", "8080"), others = listOf(holder)))
        assertEquals(1, check(portBody("80/tcp", "10.0.0.1", "8080"), others = listOf(holder)).size)
    }

    @Test
    fun protocolMustMatch() {
        val holder = c("1", "dns", ports = listOf(PublishedPort("0.0.0.0", 53, 53, "udp")))
        assertEquals(emptyList(), check(portBody("53/tcp", "", "53"), others = listOf(holder)))
        assertEquals(1, check(portBody("53/udp", "", "53"), others = listOf(holder)).size)
    }

    @Test
    fun stoppedHolderWarnsOnly() {
        val stopped = c("2", "old", ContainerState.Exited)
        val result = check(
            portBody("80/tcp", "", "8080"),
            others = listOf(stopped),
            stopped = mapOf("old" to listOf(PublishedPort("0.0.0.0", 8080, 80, "tcp"))),
        )
        assertEquals(listOf(Conflict.HostPortInUse("8080", "tcp", "old", false)), result)
        assertFalse(result.single().blocking)
    }

    @Test
    fun excludedContainerStoppedBindingsIgnored() {
        val self = c("2", "old", ContainerState.Exited)
        val result = check(
            portBody("80/tcp", "", "8080"),
            name = "old",
            others = listOf(self),
            exclude = "2",
            stopped = mapOf("old" to listOf(PublishedPort("0.0.0.0", 8080, 80, "tcp"))),
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun portRangesAreExpandedAndEmptyHostPortIgnored() {
        val holder = c("1", "web", ports = listOf(PublishedPort("0.0.0.0", 8005, 80, "tcp")))
        val result = check(portBody("80/tcp", "", "8000-8010"), others = listOf(holder))
        assertEquals(listOf(Conflict.HostPortInUse("8005", "tcp", "web", true)), result)
        assertEquals(emptyList(), check(portBody("80/tcp", "", ""), others = listOf(holder)))
    }

    @Test
    fun fixedIpInUseBlocks() {
        val b = body("""{"NetworkingConfig":{"EndpointsConfig":{"backend":{"IPAMConfig":{"IPv4Address":"172.18.0.5"}}}}}""")
        val n = net("id1" to NetworkMember("db", "172.18.0.5/16", null), "id2" to NetworkMember("x", "172.18.0.9/16", null))
        val result = check(b, networks = listOf(n))
        assertEquals(listOf(Conflict.FixedIpInUse("backend", "172.18.0.5", "db", running = true)), result)
        assertTrue(result.single().blocking)
        assertEquals(emptyList(), check(b, networks = listOf(n), exclude = "id1"))
    }

    @Test
    fun fixedIpv6InUseBlocks() {
        val b = body("""{"NetworkingConfig":{"EndpointsConfig":{"backend":{"IPAMConfig":{"IPv6Address":"fd00::5"}}}}}""")
        val n = net("id1" to NetworkMember("db", null, "fd00::5/64"))
        assertEquals(listOf(Conflict.FixedIpInUse("backend", "fd00::5", "db", running = true)), check(b, networks = listOf(n)))
    }

    @Test
    fun fixedIpHeldByStoppedContainerBlocks() {
        val b = body("""{"NetworkingConfig":{"EndpointsConfig":{"backend":{"IPAMConfig":{"IPv4Address":"172.18.0.5"}}}}}""")
        val others = listOf(c("id1", "/db", state = ContainerState.Exited))
        val stoppedIps = mapOf("/db" to listOf("backend" to "172.18.0.5"))
        val result = check(b, others = others, networks = listOf(net()), stoppedIps = stoppedIps)
        assertEquals(listOf(Conflict.FixedIpInUse("backend", "172.18.0.5", "db", running = false)), result)
        // Copy / New: blocks even though the holder is stopped (one of the two could not start).
        assertTrue(result.single().blocking)
        // Another network, another IP, or the container a Replace overwrites: no clash.
        assertEquals(emptyList(), check(b, others = others, stoppedIps = mapOf("/db" to listOf("frontend" to "172.18.0.5"))))
        assertEquals(emptyList(), check(b, others = others, stoppedIps = mapOf("/db" to listOf("backend" to "172.18.0.6"))))
        assertEquals(emptyList(), check(b, others = others, exclude = "id1", stoppedIps = stoppedIps))
    }

    @Test
    fun sharedNamedVolumeWarns() {
        val b = body("""{"HostConfig":{"Binds":["db:/data","/srv:/srv","anon:/x"],"Mounts":[{"Type":"volume","Source":"cache","Target":"/c"}]}}""")
        val others = listOf(c("1", "web", volumes = listOf("db", "cache", "other")))
        val result = check(b, others = others)
        assertEquals(setOf(Conflict.SharedNamedVolume("db"), Conflict.SharedNamedVolume("cache")), result.toSet())
        assertTrue(result.none { it.blocking })
    }

    @Test
    fun anonymousVolumeNotReportedAsShared() {
        val hex = "a".repeat(64)
        val b = body("""{"HostConfig":{"Mounts":[{"Type":"volume","Source":"$hex","Target":"/c"}]}}""")
        assertEquals(emptyList(), check(b, others = listOf(c("1", "web", volumes = listOf(hex)))))
    }
}
