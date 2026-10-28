package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.cleanup.RECREATE_IN_PROGRESS_MESSAGE
import dev.silas.portainer.app.recreate.SessionKey
import dev.silas.portainer.core.recreate.ChangeSummary
import dev.silas.portainer.core.model.ImageRef
import kotlinx.serialization.json.JsonObject
import dev.silas.portainer.core.recreate.RecreatePlan
import dev.silas.portainer.app.drain
import dev.silas.portainer.app.settle
import dev.silas.portainer.app.support.CONNECTION_DEPENDENCY_REMINDER
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.model.StackType
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StackActionsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private val opRequests: List<String> get() = requests.filter { !it.startsWith("GET") }

    /** `Status` of stack 5; changes when it is started or stopped. */
    @Volatile private var status = 1
    @Volatile private var deleted = false
    private var gitStack = false
    private var fileStatus = HttpStatusCode.OK
    private var fileContent = "services:\n  web:\n    image: nginx\n"
    private var containersJson = """[
        {"Id":"c1","Names":["/shop-web-1"],"Image":"nginx","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"shop"}},
        {"Id":"c3","Names":["/other"],"Image":"redis","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"other"}}
    ]"""
    /** When set, the op request waits for it. */
    private var opGate: CompletableDeferred<Unit>? = null
    private var opFails = false
    /** Holds a recreate's pull. */
    private val pullGate = CompletableDeferred<Unit>()
    private var testEnv: TestEnv? = null

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() {
        opGate?.complete(Unit)
        pullGate.complete(Unit)
        Dispatchers.resetMain()
    }

    private suspend fun env(): TestEnv = testEnv ?: TestEnv { req ->
        val path = req.url.encodedPath
        requestLog.update { it + "${req.method.value} $path" }
        when {
            path == "/api/status" -> respond("""{"Version":"2.45.3"}""", HttpStatusCode.OK, json)
            path == "/api/stacks" -> respond("[]", HttpStatusCode.OK, json)
            path == "/api/stacks/5" && req.method == HttpMethod.Get ->
                if (deleted) {
                    respond("""{"message":"Unable to find a stack"}""", HttpStatusCode.NotFound, json)
                } else {
                    val git = if (gitStack) ""","GitConfig":{"URL":"https://git.example.com/shop.git","ConfigFilePath":"compose.yml"}""" else ""
                    respond("""{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":$status$git}""", HttpStatusCode.OK, json)
                }
            path == "/api/stacks/5/file" ->
                if (fileStatus == HttpStatusCode.OK) {
                    respond("""{"StackFileContent":"${fileContent.replace("\n", "\\n")}"}""", HttpStatusCode.OK, json)
                } else {
                    respond("""{"message":"Stack git settings have changed"}""", fileStatus, json)
                }
            // An earlier stack named "shop", already deleted.
            path == "/api/stacks/4" ->
                if (req.method == HttpMethod.Delete) respond("", HttpStatusCode.NoContent) else respond("""{"message":"Unable to find a stack"}""", HttpStatusCode.NotFound, json)
            path.startsWith("/api/stacks/5") -> {
                opGate?.await()
                if (opFails) throw RuntimeException("connection reset")
                when {
                    req.method == HttpMethod.Delete -> {
                        deleted = true
                        respond("", HttpStatusCode.NoContent)
                    }
                    path.endsWith("/start") -> {
                        status = 1
                        respond("{}", HttpStatusCode.OK, json)
                    }
                    else -> {
                        status = 2
                        respond("{}", HttpStatusCode.OK, json)
                    }
                }
            }
            path.endsWith("/containers/json") -> respond(containersJson, HttpStatusCode.OK, json)
            path.endsWith("/images/create") -> {
                pullGate.await()
                respond("{}", HttpStatusCode.OK, json)
            }
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }.also {
        it.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        testEnv = it
    }

    private val vms = mutableListOf<StackDetailViewModel>()

    /** Runs [block], then lets the view models' loads finish ([drain]) before the Main dispatcher is reset. */
    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        block()
        drain { vms.all { !it.state.value.loading && !it.state.value.starting } }
    }

    private suspend fun TestScope.loaded(stackId: Int? = 5, name: String = "shop"): StackDetailViewModel {
        val vm = StackDetailViewModel(env().container, "s1", 1, stackId, name).also { vms += it }
        settle { !vm.state.value.loading && (vm.state.value.stack != null || vm.state.value.error != null) }
        return vm
    }

    private suspend fun TestScope.idle(vm: StackDetailViewModel) =
        settle { vm.state.value.running == null && !vm.state.value.starting && !vm.state.value.loading }

    // ---- pure rules ----

    private fun stack(status: StackStatus = StackStatus.Active, control: StackControl = StackControl.Full) = Stack(
        id = if (control == StackControl.Full) 5 else null, name = "shop", type = StackType.Compose, endpointId = 1,
        status = status, control = control, git = null, autoUpdate = null, env = emptyList(),
        createdAt = null, updatedAt = null, updatedBy = null, fromTemplate = false,
    )

    @Test
    fun deleteNeedsTheExactTypedName() {
        val confirm = StackConfirm(StackOp.Delete, "shop")
        assertTrue(confirm.needsTypedName)
        for (typed in listOf("", "Shop", "SHOP", " shop", "shop ", "sho")) {
            assertFalse(confirm.copy(typedName = typed).canConfirm, "'$typed'")
        }
        assertTrue(confirm.copy(typedName = "shop").canConfirm)
    }

    @Test
    fun everyConfirmShowsTheConnectionReminder() {
        for (op in listOf(StackOp.Stop, StackOp.Delete)) {
            assertTrue(needsConfirm(op))
            assertEquals(CONNECTION_DEPENDENCY_REMINDER, StackConfirm(op, "shop").reminder)
        }
        assertFalse(needsConfirm(StackOp.Start))
        assertFailsWith<IllegalArgumentException> { StackConfirm(StackOp.Start, "shop") }
        assertFalse(StackConfirm(StackOp.Stop, "shop").needsTypedName)
        assertTrue(StackConfirm(StackOp.Stop, "shop").canConfirm)
        // Delete keeps volumes (CE has no option to remove them): the confirm says so.
        assertTrue(StackConfirm(StackOp.Delete, "shop").body.contains("volumes are kept"))
    }

    @Test
    fun opsFollowTheStatusAndExternalStacksGetNone() {
        assertEquals(listOf(StackOp.Stop, StackOp.Delete), availableStackOps(StackDetailState(stack = stack(StackStatus.Active))))
        assertEquals(listOf(StackOp.Start, StackOp.Delete), availableStackOps(StackDetailState(stack = stack(StackStatus.Inactive))))
        assertEquals(listOf(StackOp.Stop, StackOp.Delete), availableStackOps(StackDetailState(stack = stack(StackStatus.Error))))
        assertEquals(emptyList(), availableStackOps(StackDetailState(stack = stack(StackStatus.Deploying))))
        assertEquals(emptyList(), availableStackOps(StackDetailState(stack = stack(control = StackControl.Limited))))
        assertEquals(EXTERNAL_STACK_MESSAGE, stackOpRefusal(StackDetailState(stack = stack(control = StackControl.Limited)), StackOp.Stop))
    }

    @Test
    fun protectionBlocksEveryOpStartIncluded() {
        val protected = StackDetailState(stack = stack(), protected = true)
        val unknown = StackDetailState(stack = stack(), protectionUnknown = true)
        for (op in listOf(StackOp.Start, StackOp.Stop, StackOp.Delete)) {
            assertEquals(PROTECTED_IMAGE_MESSAGE, stackOpRefusal(protected, op))
            assertEquals(PROTECTION_UNKNOWN_MESSAGE, stackOpRefusal(unknown, op))
            assertNull(stackOpRefusal(StackDetailState(stack = stack()), op))
        }
    }

    // ---- view model ----

    @Test
    fun protectedStackRefusesStopAndDeleteWithoutARequest() = vmTest {
        containersJson = """[
            {"Id":"c1","Names":["/shop-agent-1"],"Image":"portainer/agent:2.45.3","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"shop"}}
        ]"""
        val vm = loaded()
        assertTrue(vm.state.value.protected)
        for (op in listOf(StackOp.Stop, StackOp.Delete)) {
            vm.request(op)
            assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
            assertNull(vm.state.value.confirm)
            vm.clearError()
        }
        idle(vm)
        assertEquals(emptyList(), opRequests)
    }

    @Test
    fun gitStackWhoseFileCantBeReadRefusesStopAndDelete() = vmTest {
        gitStack = true
        fileStatus = HttpStatusCode.Conflict
        val vm = loaded()
        assertTrue(vm.state.value.protectionUnknown)
        assertFalse(vm.state.value.protected)
        vm.request(StackOp.Stop)
        assertEquals(PROTECTION_UNKNOWN_MESSAGE, vm.state.value.error)
        vm.clearError()
        vm.request(StackOp.Delete)
        assertEquals(PROTECTION_UNKNOWN_MESSAGE, vm.state.value.error)
        assertNull(vm.state.value.confirm)
        assertEquals(emptyList(), opRequests)
    }

    @Test
    fun externalStackOffersNoActions() = vmTest {
        val vm = loaded(stackId = null, name = "other")
        assertEquals(emptyList(), vm.state.value.ops)
        assertFalse(vm.state.value.protectionUnknown)
        vm.request(StackOp.Stop)
        assertNull(vm.state.value.confirm)
        assertEquals(emptyList(), opRequests)
    }

    @Test
    fun stopAsksThenRunsAndReportsTheConfirmedResult() = vmTest {
        val vm = loaded()
        vm.request(StackOp.Stop)
        val confirm = assertNotNull(vm.state.value.confirm)
        assertEquals(StackOp.Stop, confirm.op)
        assertEquals(emptyList(), opRequests)
        vm.confirm()
        settle { vm.state.value.notice != null }
        assertEquals("Stack stopped", vm.state.value.notice)
        idle(vm)
        assertEquals(listOf("POST /api/stacks/5/stop"), opRequests)
        assertEquals(StackStatus.Inactive, vm.state.value.stack?.status)
        assertEquals(listOf(StackOp.Start, StackOp.Delete), vm.state.value.ops)
        val session = env().container.stackSessions.progress(StackKey("s1", 1, "shop"))
        assertEquals(StackProgress(StackOp.Stop, running = false, error = null, stackId = 5), session.value)
        vm.clearNotice() // seen: forgotten, so it isn't shown again
        assertNull(session.value)
    }

    @Test
    fun startRunsWithoutAConfirm() = vmTest {
        status = 2
        val vm = loaded()
        vm.request(StackOp.Start)
        assertNull(vm.state.value.confirm)
        settle { vm.state.value.notice != null }
        assertEquals("Stack started", vm.state.value.notice)
        assertEquals(listOf("POST /api/stacks/5/start"), opRequests)
    }

    @Test
    fun deleteNeedsTheTypedNameThenGoesBackToTheListWhichNoLongerShowsIt() = vmTest {
        val vm = loaded()
        vm.request(StackOp.Delete)
        vm.setTypedName("Shop")
        vm.confirm()
        assertNotNull(vm.state.value.confirm) // wrong case: nothing happens
        assertEquals(emptyList(), opRequests)
        vm.setTypedName("shop")
        vm.confirm()
        settle { vm.state.value.deleted }
        assertEquals(listOf("DELETE /api/stacks/5"), opRequests)
        vm.navigated()
        assertFalse(vm.state.value.deleted)

        val list = StacksViewModel(env().container, "s1", 1)
        settle { !list.state.value.loading }
        assertTrue(list.state.value.stacks.none { it.id == 5 })
        // The list says what happened when it shows (also when the screen came from a container).
        list.onScreenResumed()
        assertEquals("Deleted shop", list.state.value.notice)
    }

    @Test
    fun failureMidOpShowsTheErrorAndClaimsNothing() = vmTest {
        opFails = true
        val vm = loaded()
        vm.request(StackOp.Delete)
        vm.setTypedName("shop")
        vm.confirm()
        settle { vm.state.value.failure != null }
        val (op, message) = assertNotNull(vm.state.value.failure)
        assertEquals(StackOp.Delete, op)
        assertEquals("Could not reach Portainer", message)
        assertFalse(vm.state.value.deleted)
        assertNull(vm.state.value.notice)
        assertNull(vm.state.value.running)
        vm.clearFailure()
        assertNull(vm.state.value.failure)
    }

    @Test
    fun newViewModelReattachesToARunningOpAndThenItsResult() = vmTest {
        opGate = CompletableDeferred()
        val first = loaded()
        first.request(StackOp.Stop)
        first.confirm()
        settle { first.state.value.running == StackOp.Stop }

        val second = loaded()
        assertEquals(StackOp.Stop, second.state.value.running)
        assertTrue(second.state.value.ops.isNotEmpty())
        second.request(StackOp.Delete) // ignored while running
        assertNull(second.state.value.confirm)
        opGate!!.complete(Unit)
        settle { second.state.value.notice != null && first.state.value.notice != null }
        assertEquals(1, opRequests.size)
    }

    @Test
    fun newViewModelShowsAResultThatFinishedWhileNoScreenWasOpen() = vmTest {
        opFails = true
        val sessions = env().container.stackSessions
        val key = StackKey("s1", 1, "shop")
        assertTrue(sessions.start(key, 5, StackOp.Stop))
        settle { sessions.progress(key).value?.running == false }

        val vm = loaded()
        settle { vm.state.value.failure != null }
        assertEquals(StackOp.Stop, vm.state.value.failure?.first)
        vm.clearFailure()
        assertNull(sessions.progress(key).value) // seen: not shown again
    }

    @Test
    fun confirmIsRefusedWhileARecreateRunsOnTheEndpoint() = vmTest {
        val vm = loaded()
        vm.request(StackOp.Stop)
        assertNotNull(vm.state.value.confirm)
        val container = env().container
        // The recreate's pull waits on the gate, so it keeps running.
        val plan = RecreatePlan(
            containerName = "web", wasRunning = false, createBody = JsonObject(emptyMap()),
            extraNetworks = emptyMap(), image = ImageRef.parse("nginx"), pull = true,
            registryId = null, changes = ChangeSummary(null, emptyList(), emptyList(), emptyList(), emptyList()),
            warnings = emptyList(),
        )
        assertTrue(container.recreateSessions.start(SessionKey("s1", 1, "c1"), container.dedicatedClient("s1"), "c1", plan))
        vm.confirm()
        settle { vm.state.value.error != null && !vm.state.value.starting }
        assertEquals(RECREATE_IN_PROGRESS_MESSAGE, vm.state.value.error)
        assertEquals(emptyList(), opRequests.filter { it.contains("/api/stacks") })
    }

    @Test
    fun stackWithoutContainersProtectedByItsFileRefusesEverything() = vmTest {
        containersJson = "[]"
        status = 2
        fileContent = "services:\n  agent:\n    image: portainer/agent:2.45.3\n"
        val vm = loaded()
        assertEquals(StackStatus.Inactive, vm.state.value.stack?.status)
        assertTrue(vm.state.value.containers.isEmpty())
        assertTrue(vm.state.value.protected)
        assertEquals(listOf(StackOp.Start, StackOp.Delete), vm.state.value.ops)
        for (op in listOf(StackOp.Delete, StackOp.Start)) {
            vm.request(op)
            assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
            assertNull(vm.state.value.confirm)
            vm.clearError()
        }
        idle(vm)
        assertEquals(emptyList(), opRequests)
    }

    @Test
    fun stackWithoutContainersCanBeStartedAndDeleted() = vmTest {
        containersJson = "[]"
        status = 2
        val vm = loaded()
        assertTrue(vm.state.value.containers.isEmpty())
        assertFalse(vm.state.value.protected)
        vm.request(StackOp.Start)
        settle { vm.state.value.notice != null }
        assertEquals("Stack started", vm.state.value.notice)
        assertEquals(listOf("POST /api/stacks/5/start"), opRequests)
        vm.clearNotice()
        idle(vm)
        vm.request(StackOp.Delete)
        vm.setTypedName("shop")
        vm.confirm()
        settle { vm.state.value.deleted }
        assertEquals(listOf("POST /api/stacks/5/start", "DELETE /api/stacks/5"), opRequests)
        assertNull(vm.state.value.failure)
    }

    @Test
    fun resultOfAnEarlierStackWithTheSameNameIsClearedNotShown() = vmTest {
        val sessions = env().container.stackSessions
        val key = StackKey("s1", 1, "shop")
        // Stack 4 "shop" was deleted with no screen open: its result is still held.
        sessions.start(key, 4, StackOp.Delete)
        settle { sessions.progress(key).value?.running == false }
        assertNull(sessions.progress(key).value?.error, sessions.progress(key).value.toString())

        val vm = loaded() // the new "shop", id 5
        settle { sessions.progress(key).value == null }
        assertFalse(vm.state.value.deleted)
        assertNull(vm.state.value.failure)
        assertNull(vm.state.value.notice)
    }

    @Test
    fun requestIsRefusedBeforeTheConfirmWhileAnotherSessionRuns() = vmTest {
        val vm = loaded()
        val container = env().container
        assertTrue(container.recreateSessions.start(SessionKey("s1", 1, "c1"), container.dedicatedClient("s1"), "c1", recreatePlan()))
        vm.request(StackOp.Stop)
        assertNull(vm.state.value.confirm)
        assertEquals(RECREATE_IN_PROGRESS_MESSAGE, vm.state.value.error)
    }

    private fun recreatePlan() = RecreatePlan(
        containerName = "web", wasRunning = false, createBody = JsonObject(emptyMap()),
        extraNetworks = emptyMap(), image = ImageRef.parse("nginx"), pull = true,
        registryId = null, changes = ChangeSummary(null, emptyList(), emptyList(), emptyList(), emptyList()),
        warnings = emptyList(),
    )
}
