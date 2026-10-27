package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.StackDetail
import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.detail.ContainerDetailViewModel
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.model.StackType
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class OpenStackLinkTest {
    private fun stack(id: Int?, name: String) = Stack(
        id = id, name = name, type = StackType.Compose, endpointId = 1, status = StackStatus.Active,
        control = if (id == null) StackControl.Limited else StackControl.Full,
        git = null, autoUpdate = null, env = emptyList(), createdAt = null, updatedAt = null, updatedBy = null, fromTemplate = false,
    )

    private val stacks = listOf(stack(5, "shop"), stack(null, "legacy"))

    @Test
    fun managedStackLinksWithItsId() {
        assertEquals(StackDetail("s1", 1, 5, "shop"), stackLinkFor("shop", stacks, "s1", 1))
    }

    @Test
    fun externalStackLinksByNameWithNullId() {
        assertEquals(StackDetail("s1", 1, null, "legacy"), stackLinkFor("legacy", stacks, "s1", 1))
    }

    @Test
    fun nonMemberHasNoLink() {
        assertNull(stackLinkFor(null, stacks, "s1", 1))
        assertNull(stackLinkFor("other", stacks, "s1", 1))
        assertNull(stackLinkFor("shop", emptyList(), "s1", 1))
    }

    @Test
    fun sameNamedStackOnAnotherEndpointIsNotLinked() {
        val elsewhere = listOf(stack(9, "shop").copy(endpointId = 2))
        assertNull(stackLinkFor("shop", elsewhere, "s1", 1))
        assertEquals(StackDetail("s1", 2, 9, "shop"), stackLinkFor("shop", elsewhere, "s1", 2))
    }

    // --- view model ---

    private val requestLog = MutableStateFlow(emptyList<String>())
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val inspect = """{"Id":"abc","Name":"/web","State":{"Status":"running"},
      "Config":{"Image":"nginx:1.27","Labels":{"com.docker.compose.project":"shop"}},"HostConfig":{},"NetworkSettings":{}}"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(stacksOk: Boolean): TestEnv {
        val env = TestEnv { req ->
            val path = req.url.encodedPath
            requestLog.update { it + path }
            when {
                path == "/api/stacks" ->
                    if (stacksOk) respond("""[{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":1}]""", HttpStatusCode.OK, json)
                    else respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json)
                path.endsWith("/containers/json") -> respond("[]", HttpStatusCode.OK, json)
                path.endsWith("/json") -> respond(inspect, HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    @Test
    fun detailLoadsTheStackLink() = runTest {
        val vm = ContainerDetailViewModel(env(stacksOk = true).container, "s1", 1, "abc")
        settle { vm.state.value.stackLink != null }
        assertEquals(StackDetail("s1", 1, 5, "shop"), assertNotNull(vm.state.value.stackLink))
    }

    @Test
    fun refreshingTheDetailDoesNotLookTheStackUpAgain() = runTest {
        val vm = ContainerDetailViewModel(env(stacksOk = true).container, "s1", 1, "abc")
        settle { vm.state.value.stackLink != null }
        vm.refresh()
        settle { vm.state.value.loading }
        settle { !vm.state.value.loading }
        assertEquals(1, requestLog.value.count { it == "/api/stacks" })
    }

    @Test
    fun aStacksLoadFailureHidesTheLinkWithoutAnError() = runTest {
        val vm = ContainerDetailViewModel(env(stacksOk = false).container, "s1", 1, "abc")
        settle { vm.state.value.overview != null && !vm.state.value.loading }
        // The lookup runs apart from the overview: wait until its request has been answered (and failed).
        settle { "/api/stacks" in requestLog.value }
        assertNull(vm.state.value.stackLink)
        assertNull(vm.state.value.error)
        assertEquals("shop", vm.state.value.overview?.stack)
    }
}
