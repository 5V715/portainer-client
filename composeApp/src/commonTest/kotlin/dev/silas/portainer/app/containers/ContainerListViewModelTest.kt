package dev.silas.portainer.app.containers

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.containers.ContainerFilter
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContainerListViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()
    private var actionStatus = HttpStatusCode.NoContent
    private var actionBody = ""

    private val listJson = """[
        {"Id":"a1","Names":["/web"],"Image":"nginx","State":"running","Status":"Up 1 hour"},
        {"Id":"b2","Names":["/db"],"Image":"postgres","State":"exited","Status":"Exited (0)"},
        {"Id":"p3","Names":["/portainer"],"Image":"portainer/portainer-ce:latest","State":"running","Status":"Up 1 hour"}
    ]"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { req ->
            requests += "${req.method.value} ${req.url.encodedPath}"
            if (req.method == HttpMethod.Get) {
                respond(listJson, HttpStatusCode.OK, json)
            } else {
                respond(actionBody, actionStatus, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    private fun actionCalls() = requests.filter { !it.startsWith("GET") }

    @Test
    fun refreshLoadsAndFilters() = runTest {
        val vm = ContainerListViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        assertEquals(listOf("db", "portainer", "web"), vm.state.value.visible.map { it.name })
        vm.setFilter(ContainerFilter(states = setOf(ContainerState.Exited)))
        assertEquals(listOf("db"), vm.state.value.visible.map { it.name })
        vm.setFilter(ContainerFilter(query = "ngi"))
        assertEquals(listOf("web"), vm.state.value.visible.map { it.name })
    }

    @Test
    fun stopOnProtectedImageIsRefusedWithoutApiCall() = runTest {
        val vm = ContainerListViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        vm.stop("p3")
        vm.remove("p3")
        settle { vm.state.value.error != null }
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
        assertTrue(actionCalls().isEmpty())
        assertTrue(vm.state.value.busyIds.isEmpty())
    }

    @Test
    fun actionFailureSetsErrorAndClearsBusy() = runTest {
        val env = env()
        val vm = ContainerListViewModel(env.container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        actionStatus = HttpStatusCode.Conflict
        actionBody = """{"message":"cannot remove a running container"}"""
        vm.remove("a1")
        settle { vm.state.value.error != null }
        assertEquals("cannot remove a running container", vm.state.value.error)
        assertTrue(vm.state.value.busyIds.isEmpty())
        assertEquals(listOf("DELETE /api/endpoints/1/docker/containers/a1"), actionCalls())
        vm.clearError()
        assertNull(vm.state.value.error)
    }

    @Test
    fun successfulActionTriggersRefresh() = runTest {
        val vm = ContainerListViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        val listsBefore = requests.count { it.startsWith("GET") }
        vm.restart("a1")
        settle { requests.count { it.startsWith("GET") } > listsBefore && vm.state.value.busyIds.isEmpty() }
        assertNotNull(actionCalls().singleOrNull { it.endsWith("/containers/a1/restart") })
        assertNull(vm.state.value.error)
    }
}
