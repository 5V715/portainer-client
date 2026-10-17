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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    private var listBody: (Int) -> Pair<Long, String> = { 0L to listJson }
    private var listCalls = 0
    private var inspectStatus = HttpStatusCode.OK
    private var inspectImage = "nginx"
    private var actionDelay = 0L
    private val queries = mutableListOf<String>()

    private val listJson = """[
        {"Id":"a1","Names":["/web"],"Image":"nginx","State":"running","Status":"Up 1 hour"},
        {"Id":"b2","Names":["/db"],"Image":"postgres","State":"exited","Status":"Exited (0)"},
        {"Id":"p3","Names":["/portainer"],"Image":"portainer/portainer-ce:latest","State":"running","Status":"Up 1 hour"},
        {"Id":"s4","Names":["/moved"],"Image":"sha256:${"a".repeat(64)}","State":"running","Status":"Up 1 hour","Labels":{"com.docker.compose.project":"gone"}}
    ]"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { req ->
            requests += "${req.method.value} ${req.url.encodedPath}"
            queries += req.url.encodedQuery
            val path = req.url.encodedPath
            if (req.method == HttpMethod.Get && path.endsWith("/containers/json")) {
                val (delayMs, body) = listBody(++listCalls)
                delay(delayMs)
                respond(body, HttpStatusCode.OK, json)
            } else if (req.method == HttpMethod.Get) {
                respond("""{"Config":{"Image":"$inspectImage"}}""", inspectStatus, json)
            } else {
                delay(actionDelay)
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
        assertEquals(listOf("db", "moved", "portainer", "web"), vm.state.value.visible.map { it.name })
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

    private suspend fun kotlinx.coroutines.test.TestScope.loaded(env: TestEnv): ContainerListViewModel {
        val vm = ContainerListViewModel(env.container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        return vm
    }

    private fun gets() = requests.count { it.startsWith("GET") && it.endsWith("/containers/json") }

    @Test
    fun slowEarlierLoadDoesNotOverwriteLaterOne() = runTest {
        listBody = { n ->
            if (n == 1) 400L to """[{"Id":"old","Names":["/old"],"Image":"x","State":"running","Status":""}]"""
            else 0L to """[{"Id":"new","Names":["/new"],"Image":"x","State":"running","Status":""}]"""
        }
        val env = env()
        val vm = ContainerListViewModel(env.container, "s1", 1)
        settle { listCalls >= 1 }
        vm.refresh()
        settle { vm.state.value.items.map { it.name } == listOf("new") && !vm.state.value.loading }
        withContext(Dispatchers.Default) { delay(700) }
        advanceUntilIdle()
        assertEquals(listOf("new"), vm.state.value.items.map { it.name })
        assertTrue(!vm.state.value.loading)
    }

    @Test
    fun imageIdIsResolvedByInspectAndProtectedIsRefused() = runTest {
        inspectImage = "portainer/agent:2"
        val vm = loaded(env())
        vm.stop("s4")
        settle { vm.state.value.error != null && vm.state.value.busyIds.isEmpty() }
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
        assertTrue(actionCalls().isEmpty())
    }

    @Test
    fun imageIdThatInspectsAsOrdinaryProceeds() = runTest {
        val vm = loaded(env())
        vm.stop("s4")
        settle { actionCalls().isNotEmpty() && vm.state.value.busyIds.isEmpty() }
        assertEquals(listOf("POST /api/endpoints/1/docker/containers/s4/stop"), actionCalls())
    }

    @Test
    fun failedInspectRefusesTheAction() = runTest {
        inspectStatus = HttpStatusCode.InternalServerError
        val vm = loaded(env())
        vm.remove("s4")
        settle { vm.state.value.error != null && vm.state.value.busyIds.isEmpty() }
        assertTrue(actionCalls().isEmpty())
    }

    @Test
    fun unknownIdIsANoOp() = runTest {
        val vm = loaded(env())
        vm.stop("nope")
        vm.start("nope")
        advanceUntilIdle()
        assertTrue(actionCalls().isEmpty())
        assertTrue(vm.state.value.busyIds.isEmpty())
    }

    @Test
    fun doubleTapCallsApiOnce() = runTest {
        actionDelay = 200
        val vm = loaded(env())
        vm.restart("a1")
        vm.restart("a1")
        settle { actionCalls().isNotEmpty() && vm.state.value.busyIds.isEmpty() }
        assertEquals(1, actionCalls().size)
    }

    @Test
    fun startAndRestartAllowedOnProtectedImage() = runTest {
        val vm = loaded(env())
        vm.start("p3")
        settle { actionCalls().size == 1 && vm.state.value.busyIds.isEmpty() }
        vm.restart("p3")
        settle { actionCalls().size == 2 && vm.state.value.busyIds.isEmpty() }
        assertEquals(
            listOf("POST /api/endpoints/1/docker/containers/p3/start", "POST /api/endpoints/1/docker/containers/p3/restart"),
            actionCalls(),
        )
    }

    @Test
    fun failedActionDoesNotRefresh() = runTest {
        val vm = loaded(env())
        val before = gets()
        actionStatus = HttpStatusCode.InternalServerError
        actionBody = """{"message":"boom"}"""
        vm.start("b2")
        settle { vm.state.value.error != null && vm.state.value.busyIds.isEmpty() }
        advanceUntilIdle()
        assertEquals(before, gets())
    }

    @Test
    fun removeDoesNotForce() = runTest {
        val vm = loaded(env())
        vm.remove("a1")
        settle { actionCalls().isNotEmpty() }
        val idx = requests.indexOfFirst { it.startsWith("DELETE") }
        assertTrue("force" !in queries[idx])
    }

    @Test
    fun stackFilterResetsWhenStackDisappears() = runTest {
        val vm = loaded(env())
        vm.setFilter(ContainerFilter(stack = "gone"))
        listBody = { 0L to """[{"Id":"a1","Names":["/web"],"Image":"nginx","State":"running","Status":""}]""" }
        vm.refresh()
        settle { vm.state.value.items.size == 1 && !vm.state.value.loading }
        assertNull(vm.state.value.filter.stack)
    }
}
