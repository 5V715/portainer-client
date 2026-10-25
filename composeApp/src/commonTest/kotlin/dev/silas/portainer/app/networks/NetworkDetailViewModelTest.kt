package dev.silas.portainer.app.networks

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkDetailViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    // Appended from the engine's threads while the test reads it: an atomic snapshot list.
    private val requestLog = MutableStateFlow(emptyList<String>())

    // Inspect lists only the running member; the stopped one is known only from the container list.
    private val inspectJson = """{"Id":"n1","Name":"app_net","Driver":"bridge","Scope":"local",
        "Containers":{"up1":{"Name":"api","IPv4Address":"172.18.0.2/16","IPv6Address":""}}}"""
    private var containersJson = """[
        {"Id":"up1","Names":["/api"],"Image":"api","State":"running","Status":"Up",
         "NetworkSettings":{"Networks":{"app_net":{}}}},
        {"Id":"down1","Names":["/Db"],"Image":"postgres","State":"exited","Status":"Exited (0)",
         "NetworkSettings":{"Networks":{"app_net":{}}}},
        {"Id":"other","Names":["/web"],"Image":"nginx","State":"exited","Status":"Exited (0)",
         "NetworkSettings":{"Networks":{"bridge":{}}}}
    ]"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { req ->
            val path = req.url.encodedPath
            requestLog.update { it + "${req.method.value} $path" }
            when {
                path.endsWith("/networks/n1") -> respond(inspectJson, HttpStatusCode.OK, json)
                path.endsWith("/containers/json") -> respond(containersJson, HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    @Test
    fun stoppedContainersUsingTheNetworkAreListed() = runTest {
        val vm = NetworkDetailViewModel(env().container, "s1", 1, "n1")
        settle { vm.state.value.network != null && !vm.state.value.loading }
        assertNull(vm.state.value.error)
        assertEquals(
            listOf(
                ConnectedContainer("up1", "api", running = true, ipv4 = "172.18.0.2/16", ipv6 = null),
                ConnectedContainer("down1", "Db", running = false, ipv4 = null, ipv6 = null),
            ),
            vm.state.value.connected,
        )
    }

    @Test
    fun onlyStoppedContainersStillMeanTheNetworkIsUsed() = runTest {
        containersJson = """[
            {"Id":"down1","Names":["/db"],"Image":"postgres","State":"exited","Status":"Exited (0)",
             "NetworkSettings":{"Networks":{"app_net":{}}}}
        ]"""
        val vm = NetworkDetailViewModel(env().container, "s1", 1, "n1")
        settle { vm.state.value.network != null && !vm.state.value.loading }
        // The inspect's running member that the list no longer shows is still kept, with its IP.
        assertEquals(listOf("api", "db"), vm.state.value.connected.map { it.name })
        assertEquals(listOf(true, false), vm.state.value.connected.map { it.running })
        assertEquals(2, requestLog.value.size)
    }
}
