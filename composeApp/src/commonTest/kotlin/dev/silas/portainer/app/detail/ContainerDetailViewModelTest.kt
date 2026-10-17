package dev.silas.portainer.app.detail

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContainerDetailViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var status = HttpStatusCode.OK

    private val inspect = """{
      "Id":"abc","Name":"/web","Created":"2026-10-17T09:00:00.123456789Z",
      "State":{"Status":"running","StartedAt":"2026-10-17T10:00:00.5Z","Health":{"Status":"healthy"}},
      "Config":{"Image":"nginx:1.27","Tty":true,"Env":["A=1","B=two"],
        "Labels":{"com.docker.compose.project":"shop","x":"y"},"ExposedPorts":{"80/tcp":{},"443/tcp":{},"9000/tcp":{}}},
      "HostConfig":{"RestartPolicy":{"Name":"unless-stopped","MaximumRetryCount":0}},
      "Mounts":[{"Type":"volume","Name":"data","Source":"/var/lib/docker/volumes/data/_data","Destination":"/data","RW":true},
                {"Type":"bind","Source":"/srv","Destination":"/srv","RW":false}],
      "NetworkSettings":{"Ports":{"80/tcp":[{"HostIp":"0.0.0.0","HostPort":"8080"}],"443/tcp":null},
        "Networks":{"bridge":{"IPAddress":"172.17.0.2"}}}
    }"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(body: String = inspect): TestEnv {
        val env = TestEnv { respond(body, status, json) }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    @Test
    fun parsesOverviewFromInspect() = runTest {
        val vm = ContainerDetailViewModel(env().container, "s1", 1, "abc")
        settle { vm.state.value.overview != null }
        val o = vm.state.value.overview!!
        assertEquals("web", o.name)
        assertEquals("nginx:1.27", o.image)
        assertEquals("running", o.state)
        assertEquals("healthy", o.health)
        assertEquals("2026-10-17 09:00:00Z", o.created)
        assertEquals("2026-10-17 10:00:00Z", o.startedAt)
        assertEquals(listOf("443/tcp", "80/tcp → 0.0.0.0:8080", "9000/tcp"), o.ports.sorted())
        assertEquals(listOf("volume data → /data (rw)", "bind /srv → /srv (ro)"), o.mounts)
        assertEquals(listOf("bridge 172.17.0.2"), o.networks)
        assertEquals(listOf("A=1", "B=two"), o.env)
        assertEquals("y", o.labels["x"])
        assertEquals("unless-stopped", o.restartPolicy)
        assertEquals("shop", o.stack)
        assertTrue(o.tty)
        assertTrue(!o.protectedImage)
    }

    @Test
    fun neverStartedAndProtectedImage() = runTest {
        val body = """{"Name":"/p","Created":"2026-10-17T09:00:00Z",
          "State":{"Status":"created","StartedAt":"0001-01-01T00:00:00Z"},
          "Config":{"Image":"portainer/portainer-ce:2.21"},"HostConfig":{"RestartPolicy":{"Name":""}}}"""
        val vm = ContainerDetailViewModel(env(body).container, "s1", 1, "p")
        settle { vm.state.value.overview != null }
        val o = vm.state.value.overview!!
        assertNull(o.startedAt)
        assertNull(o.health)
        assertNull(o.stack)
        assertEquals("no", o.restartPolicy)
        assertTrue(o.protectedImage)
        assertTrue(!o.tty)
        assertTrue(o.ports.isEmpty())
    }

    @Test
    fun failureSetsError() = runTest {
        status = HttpStatusCode.NotFound
        val vm = ContainerDetailViewModel(env("""{"message":"No such container"}""").container, "s1", 1, "abc")
        settle { !vm.state.value.loading }
        assertTrue(vm.state.value.error!!.endsWith("No such container"))
        assertNull(vm.state.value.overview)
    }
}
