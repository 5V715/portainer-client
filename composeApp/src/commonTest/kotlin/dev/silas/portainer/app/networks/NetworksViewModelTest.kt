package dev.silas.portainer.app.networks

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NetworksViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()
    private var createBody = ""
    private var listCalls = 0
    private var swarm = false
    private var infoFails = false

    private val networksJson = """[
        {"Id":"n-bridge","Name":"bridge","Driver":"bridge","Scope":"local","IPAM":{"Config":[{"Subnet":"172.17.0.0/16","Gateway":"172.17.0.1"}]}},
        {"Id":"n-host","Name":"host","Driver":"host","Scope":"local"},
        {"Id":"n-app","Name":"app_net","Driver":"bridge","Scope":"local","IPAM":{"Config":[{"Subnet":"172.20.0.0/16"}]}},
        {"Id":"n-free","Name":"free_net","Driver":"bridge","Scope":"local"},
        {"Id":"n-port","Name":"portainer_net","Driver":"bridge","Scope":"local"}
    ]"""
    // app_net is used by a stopped container; portainer_net by a Portainer container.
    private val containersJson = """[
        {"Id":"c1","Names":["/web"],"Image":"nginx","State":"exited","Status":"Exited (0)",
         "NetworkSettings":{"Networks":{"app_net":{}}}},
        {"Id":"c2","Names":["/portainer"],"Image":"portainer/portainer-ce:latest","State":"running","Status":"Up",
         "NetworkSettings":{"Networks":{"portainer_net":{}}}}
    ]"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { req ->
            val path = req.url.encodedPath
            requests += "${req.method.value} $path"
            when {
                req.method == HttpMethod.Get && path.endsWith("/networks") -> {
                    listCalls++
                    respond(networksJson, HttpStatusCode.OK, json)
                }
                req.method == HttpMethod.Get && path.endsWith("/containers/json") -> respond(containersJson, HttpStatusCode.OK, json)
                req.method == HttpMethod.Get && path.endsWith("/version") ->
                    if (infoFails) respond("{}", HttpStatusCode.InternalServerError, json) else respond("""{"ApiVersion":"1.45"}""", HttpStatusCode.OK, json)
                req.method == HttpMethod.Get && path.endsWith("/info") ->
                    respond("""{"Swarm":{"LocalNodeState":"${if (swarm) "active" else "inactive"}"}}""", HttpStatusCode.OK, json)
                req.method == HttpMethod.Post && path.endsWith("/networks/create") -> {
                    createBody = when (val b = req.body) {
                        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
                        is TextContent -> b.text
                        else -> ""
                    }
                    respond("""{"Id":"new-id"}""", HttpStatusCode.Created, json)
                }
                req.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    private fun deletes() = requests.filter { it.startsWith("DELETE") }

    private suspend fun kotlinx.coroutines.test.TestScope.loaded(): NetworksViewModel {
        val vm = NetworksViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading && vm.state.value.engineLoaded }
        return vm
    }

    @Test
    fun predefinedNeverRemovable() = runTest {
        val vm = loaded()
        val byName = vm.state.value.items.associateBy { it.network.name }
        assertFalse(byName.getValue("bridge").removable)
        assertFalse(byName.getValue("host").removable)
        assertTrue(byName.getValue("host").network.predefined)
        vm.remove("n-bridge")
        settle { vm.state.value.error != null }
        assertEquals("This network is in use, built in, or belongs to Portainer and can't be removed.", vm.state.value.error)
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun usedAndProtectedAreRefusedWithoutApiCall() = runTest {
        val vm = loaded()
        val byName = vm.state.value.items.associateBy { it.network.name }
        assertEquals(1, byName.getValue("app_net").containerCount)
        assertFalse(byName.getValue("app_net").removable)
        assertFalse(byName.getValue("portainer_net").removable)
        vm.remove("n-app")
        settle { vm.state.value.error != null }
        vm.clearError()
        vm.remove("n-port")
        settle { vm.state.value.error != null }
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun unusedNetworkIsRemovedAndListReloaded() = runTest {
        val vm = loaded()
        assertTrue(vm.state.value.items.first { it.network.id == "n-free" }.removable)
        vm.remove("n-free")
        settle { deletes().size == 1 && listCalls == 2 && !vm.state.value.loading }
        assertEquals(listOf("DELETE /api/endpoints/1/docker/networks/n-free"), deletes())
    }

    @Test
    fun overlayHiddenWhenSwarmInactive() = runTest {
        val vm = loaded()
        assertEquals(listOf("bridge", "macvlan", "ipvlan"), vm.state.value.drivers)
    }

    @Test
    fun overlayOfferedWhenSwarmActive() = runTest {
        swarm = true
        val vm = loaded()
        assertEquals(listOf("bridge", "macvlan", "ipvlan", "overlay"), vm.state.value.drivers)
    }

    @Test
    fun engineInfoFailureHidesOverlayWithoutBlocking() = runTest {
        infoFails = true
        val vm = loaded()
        assertEquals(listOf("bridge", "macvlan", "ipvlan"), vm.state.value.drivers)
        assertEquals(5, vm.state.value.items.size)
    }

    @Test
    fun nameRequired() = runTest {
        val vm = loaded()
        vm.create(NetworkForm(name = "  "))
        settle { vm.state.value.create.errors.name != null }
        assertEquals("Name is required", vm.state.value.create.errors.name)
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test
    fun cidrValidation() {
        assertTrue(isValidCidr("10.0.0.0/24"))
        assertTrue(isValidCidr("0.0.0.0/0"))
        assertTrue(isValidCidr("255.255.255.255/32"))
        assertFalse(isValidCidr("10.0.0.0"))
        assertFalse(isValidCidr("10.0.0.0/33"))
        assertFalse(isValidCidr("256.0.0.1/24"))
        assertFalse(isValidCidr("10.0.0/24"))
        assertFalse(isValidCidr("10.0.0.0/"))
        assertFalse(isValidCidr("10.0.0.0/-1"))
        assertFalse(isValidCidr("a.b.c.d/8"))
        assertTrue(isValidCidr("fd00::/64"))
        assertTrue(isValidCidr("2001:db8::/128"))
        assertFalse(isValidCidr("fd00::/129"))
        assertFalse(isValidCidr("fd00::"))
        assertFalse(isValidCidr("fd00::/x"))
    }

    @Test
    fun malformedIpv6AndLeadingZerosRejected() {
        listOf("1::2::3", ":::", "1:2:3:4:5:6:7:8:9", ":1::", "a.b::1.2.3.4", "1::2:", ":1:2:3:4:5:6:7").forEach {
            assertFalse(isValidIp(it), it)
        }
        assertTrue(isValidIp("::ffff:1.2.3.4"))
        assertTrue(isValidIp("::1"))
        assertTrue(isValidIp("1:2:3:4:5:6:7:8"))
        assertFalse(isValidIp("010.0.0.1"))
        assertFalse(isValidCidr("10.00.0.0/24"))
        assertTrue(isValidIp("10.0.0.0"))
    }

    @Test
    fun gatewayValidation() {
        assertTrue(isValidIp("172.20.0.1"))
        assertFalse(isValidIp("172.20.0.256"))
        assertFalse(isValidIp("172.20.0.1/16"))
        assertTrue(isValidIp("fd00::1"))
        assertFalse(isValidIp("fd00::g"))
        assertFalse(isValidIp("gateway"))
    }

    @Test
    fun badSubnetAndGatewayBlockCreate() = runTest {
        val vm = loaded()
        vm.create(NetworkForm(name = "n", subnet = "10.0.0.0", gateway = "nope"))
        settle { vm.state.value.create.errors.subnet != null }
        assertNotNull(vm.state.value.create.errors.gateway)
        assertNull(vm.state.value.create.errors.name)
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test
    fun validCreateSendsIpamFlagsOptionsAndLabels() = runTest {
        val vm = loaded()
        vm.create(
            NetworkForm(
                name = "mine", driver = "macvlan", subnet = "10.1.0.0/24", gateway = "10.1.0.1", ipRange = "10.1.0.0/25",
                internal = true, attachable = true,
                options = listOf("parent" to "eth0", " " to "x"), labels = listOf("env" to "prod"),
            ),
        )
        settle { vm.state.value.create.status is CreateStatus.Done }
        assertTrue(createBody.contains("\"Name\":\"mine\""), createBody)
        assertTrue(createBody.contains("\"Driver\":\"macvlan\""), createBody)
        assertTrue(createBody.contains("\"Subnet\":\"10.1.0.0/24\""), createBody)
        assertTrue(createBody.contains("\"Gateway\":\"10.1.0.1\""), createBody)
        assertTrue(createBody.contains("\"Internal\":true"), createBody)
        assertTrue(createBody.contains("\"Options\":{\"parent\":\"eth0\"}"), createBody)
        assertTrue(createBody.contains("\"Labels\":{\"env\":\"prod\"}"), createBody)
        settle { listCalls == 2 }
    }

    @Test
    fun searchFiltersByName() = runTest {
        val vm = loaded()
        vm.setQuery("APP")
        assertEquals(listOf("app_net"), vm.state.value.visible.map { it.network.name })
    }
}
