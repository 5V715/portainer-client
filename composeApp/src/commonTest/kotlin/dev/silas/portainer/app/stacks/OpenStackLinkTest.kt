package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.AfterStackGone
import dev.silas.portainer.app.ContainerDetail
import dev.silas.portainer.app.Containers
import dev.silas.portainer.app.Stacks
import dev.silas.portainer.app.afterStackGone
import dev.silas.portainer.app.Editor
import dev.silas.portainer.app.Recreate
import dev.silas.portainer.app.StackDetail
import dev.silas.portainer.app.canPopTo
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private var stacksOk = true

    private suspend fun env(): TestEnv {
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
        val vm = ContainerDetailViewModel(env().container, "s1", 1, "abc")
        settle { vm.state.value.stackLink != null }
        assertEquals(StackDetail("s1", 1, 5, "shop"), assertNotNull(vm.state.value.stackLink))
    }

    @Test
    fun refreshingTheDetailDoesNotLookTheStackUpAgain() = runTest {
        val vm = ContainerDetailViewModel(env().container, "s1", 1, "abc")
        settle { vm.state.value.stackLink != null }
        vm.refresh()
        settle { vm.state.value.loading }
        settle { !vm.state.value.loading }
        assertEquals(1, requestLog.value.count { it == "/api/stacks" })
    }

    @Test
    fun aStacksLoadFailureHidesTheLinkWithoutAnErrorAndIsRetriedOnRefresh() = runTest {
        stacksOk = false
        val vm = ContainerDetailViewModel(env().container, "s1", 1, "abc")
        settle { vm.state.value.overview != null && !vm.state.value.loading }
        // The failed lookup has finished (not only been sent): a refresh while it still runs would join it instead.
        settle { requestLog.value.count { it == "/api/stacks" } == 1 && !vm.stackLookupRunning }
        assertNull(vm.state.value.stackLink)
        assertNull(vm.state.value.error)
        assertEquals("shop", vm.state.value.overview?.stack)
        // The failed lookup is tried again by the next refresh, which would not happen if the first one had counted as done.
        stacksOk = true
        vm.refresh()
        settle { vm.state.value.stackLink != null }
        assertEquals(StackDetail("s1", 1, 5, "shop"), vm.state.value.stackLink)
        assertEquals(2, requestLog.value.count { it == "/api/stacks" })
        assertNull(vm.state.value.error)
    }

    @Test
    fun popsBackToTheContainerOnlyWhenNoEditorOrRecreateIsInBetween() {
        val target = ContainerDetail("s1", 1, "abc")
        val stack = StackDetail("s1", 1, 5, "shop")
        assertTrue(canPopTo(target, listOf(null, target, stack)))
        assertFalse(canPopTo(target, listOf(target, Editor("s1", 1, "abc"), stack)))
        assertFalse(canPopTo(target, listOf(target, Recreate("s1", 1, "abc"), stack)))
        assertFalse(canPopTo(target, listOf(null, stack)))
        // An Editor below the container does not matter.
        assertTrue(canPopTo(target, listOf(Editor("s1", 1, "abc"), target, stack)))
    }

    @Test
    fun anotherStackScreenInBetweenOrUnsavedEditsKeepTheBackStack() {
        val target = ContainerDetail("s1", 1, "abc")
        val stack = StackDetail("s1", 1, 5, "shop")
        val other = StackDetail("s1", 1, 6, "other")
        // The other stack screen may hold editor or git edits: popping would drop them.
        assertFalse(canPopTo(target, listOf(target, other, ContainerDetail("s1", 1, "def"), stack)))
        // The current stack screen is the top entry: only its own unsaved edits count for it.
        assertTrue(canPopTo(target, listOf(target, stack)))
        assertFalse(canPopTo(target, listOf(target, stack), unsavedEdits = true))
        // A stack screen below the container is not dropped.
        assertTrue(canPopTo(target, listOf(other, target, stack)))
    }

    @Test
    fun aGoneStackLeavesForItsEnvironmentsStackList() {
        val list = Stacks("s1", 1)
        val stack = StackDetail("s1", 1, 5, "shop")
        assertEquals(AfterStackGone.PopToList, afterStackGone(list, listOf(null, Containers("s1", 1), list, stack)))
        // Opened from a container: the container may be gone too; the Stacks tab replaces both.
        assertEquals(
            AfterStackGone.SwitchToListTab,
            afterStackGone(list, listOf(null, Containers("s1", 1), ContainerDetail("s1", 1, "abc"), stack)),
        )
        // Another environment's list doesn't count.
        assertEquals(AfterStackGone.ReplaceWithList, afterStackGone(list, listOf(null, Stacks("s1", 2), stack)))
    }

    @Test
    fun aGoneStackNeverDropsAnEditorOrRecreateAboveItsAnchor() {
        val list = Stacks("s1", 1)
        val containers = Containers("s1", 1)
        val stack = StackDetail("s1", 1, 5, "shop")
        val editor = Editor("s1", 1, "abc")
        val recreate = Recreate("s1", 1, "abc")
        // Popping to the list would drop the Editor/Recreate: only the gone stack screen is replaced.
        assertEquals(AfterStackGone.ReplaceWithList, afterStackGone(list, listOf(null, containers, list, editor, stack)))
        assertEquals(AfterStackGone.ReplaceWithList, afterStackGone(list, listOf(null, containers, list, recreate, stack)))
        // The same for switching tabs on top of Containers.
        assertEquals(AfterStackGone.ReplaceWithList, afterStackGone(list, listOf(null, containers, editor, stack)))
        assertEquals(AfterStackGone.ReplaceWithList, afterStackGone(list, listOf(null, containers, recreate, stack)))
        // An Editor below the anchor is not dropped.
        assertEquals(AfterStackGone.PopToList, afterStackGone(list, listOf(null, containers, editor, list, stack)))
        assertEquals(AfterStackGone.SwitchToListTab, afterStackGone(list, listOf(editor, containers, stack)))
        // The same, now-gone stack's screen in between holds nothing worth keeping.
        assertEquals(
            AfterStackGone.PopToList,
            afterStackGone(list, listOf(null, containers, list, stack, ContainerDetail("s1", 1, "abc"), stack)),
        )
        // Another stack's screen in between may hold editor or git edits.
        assertEquals(
            AfterStackGone.ReplaceWithList,
            afterStackGone(list, listOf(null, containers, list, StackDetail("s1", 1, 6, "other"), stack)),
        )
    }
}

