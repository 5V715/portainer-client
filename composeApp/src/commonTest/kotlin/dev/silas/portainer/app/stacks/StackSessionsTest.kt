package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.cleanup.CleanupKey
import dev.silas.portainer.app.cleanup.CleanupPhase
import dev.silas.portainer.app.cleanup.CleanupViewModel
import dev.silas.portainer.app.cleanup.RECREATE_IN_PROGRESS_MESSAGE
import dev.silas.portainer.app.editor.DEPLOY_IN_PROGRESS_MESSAGE
import dev.silas.portainer.app.editor.DeployKey
import dev.silas.portainer.app.editor.DeployRequest
import dev.silas.portainer.app.recreate.CLEANUP_IN_PROGRESS_MESSAGE
import dev.silas.portainer.app.recreate.SessionKey
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.api.GitAuth
import dev.silas.portainer.core.api.GitRedeployRequest
import dev.silas.portainer.core.api.GitSettingsRequest
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.stack
import dev.silas.portainer.core.cleanup.CleanupItem
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.recreate.ChangeSummary
import dev.silas.portainer.core.recreate.RecreatePlan
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StackSessionsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value

    /** `Status` codes `GET /api/stacks/5` answers in turn (the last one repeats); null answers 404. */
    private var statuses = mutableListOf<Int?>(1)
    private var statusMessage = ""
    /** When set, the op request (start/stop/delete) waits for it. */
    private var opGate: CompletableDeferred<Unit>? = null
    private var opFailsWithNetworkError = false
    private var pollFailsWithNetworkError = false
    /** `GET /api/stacks/5` includes this GitConfig; a PUT of the stack clears it unless [detachKeepsGit]. */
    @Volatile private var gitConfig = ""
    @Volatile private var detachKeepsGit = false
    /** The op request answers 500 with this body. */
    @Volatile private var opErrorBody: String? = null
    /** A slow `GET /api/stacks/5` (real time). */
    @Volatile private var pollDelayMs = 0L
    /** Holds a cleanup's DELETE, a recreate's pull, or a deploy's create. */
    private val otherGate = CompletableDeferred<Unit>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = MutableStateFlow(emptyList<PortainerClient>())

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() {
        opGate?.complete(Unit)
        otherGate.complete(Unit)
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { req ->
        val path = req.url.encodedPath
        val query = req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        requestLog.update { it + "${req.method.value} $path$query" }
        when {
            path == "/api/status" -> respond("""{"Version":"2.45.3"}""", HttpStatusCode.OK, json)
            path == "/api/stacks/5" && req.method == HttpMethod.Get -> {
                if (pollFailsWithNetworkError) throw RuntimeException("connection reset secret-api-key")
                if (pollDelayMs > 0) withContext(Dispatchers.Default) { delay(pollDelayMs) }
                val code = if (statuses.size > 1) statuses.removeAt(0) else statuses.first()
                if (code == null) {
                    respond("""{"message":"Unable to find a stack with the specified identifier inside the database"}""", HttpStatusCode.NotFound, json)
                } else {
                    val deployment = if (statusMessage.isEmpty()) "" else ""","DeploymentStatus":[{"Status":$code,"Message":"$statusMessage"}]"""
                    respond("""{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":$code$deployment$gitConfig}""", HttpStatusCode.OK, json)
                }
            }
            path.startsWith("/api/stacks/5") -> {
                opGate?.await()
                if (opFailsWithNetworkError) throw RuntimeException("connection reset secret-api-key")
                val errorBody = opErrorBody
                if (path == "/api/stacks/5" && req.method == HttpMethod.Put && !detachKeepsGit && errorBody == null) gitConfig = ""
                when {
                    errorBody != null -> respond(errorBody, HttpStatusCode.InternalServerError, json)
                    req.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                    else -> respond("{}", HttpStatusCode.OK, json)
                }
            }
            // Other sessions' first requests, held so they stay running.
            req.method == HttpMethod.Delete || path.endsWith("/images/create") || path.endsWith("/containers/create") -> {
                otherGate.await()
                respond("{}", HttpStatusCode.OK, json)
            }
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun sessions(otherWork: (String, Int) -> String? = { _, _ -> null }) = StackSessions(
        scope,
        { PortainerClient("https://a.example.com", "secret-api-key", MockEngine(handler)).also { c -> clients.update { it + c } } },
        otherWork,
        pollIntervalMs = 5,
        pollTimeoutMs = 50,
    )

    private val key = StackKey("s1", 1, "shop")

    private suspend fun kotlinx.coroutines.test.TestScope.finished(sessions: StackSessions): StackProgress {
        settle { sessions.progress(key).value?.running == false }
        return assertNotNull(sessions.progress(key).value)
    }

    private val gitJson = ""","GitConfig":{"URL":"https://g/x.git","ReferenceName":"refs/heads/main","ConfigFilePath":"compose.yml"}"""

    private fun gitSettings(password: String?) = GitSettingsRequest(
        referenceName = "refs/heads/main",
        configFilePath = "compose.yml",
        additionalFiles = emptyList(),
        auth = GitAuth("deploy", password),
        tlsSkipVerify = false,
        autoUpdate = null,
        env = listOf(EnvVar("A", "1")),
        prune = false,
    )

    @Test
    fun gitRedeployPutsThenPollsPastDeploying() = runTest {
        statuses = mutableListOf(3, 3, 1)
        val sessions = sessions()
        val op = StackOp.GitRedeploy(GitRedeployRequest("refs/heads/main", null, listOf(EnvVar("A", "1")), pullImage = true, prune = false))
        assertTrue(sessions.start(key, 5, op))
        assertNull(finished(sessions).error)
        assertEquals(listOf("PUT /api/stacks/5/git/redeploy?endpointId=1") + List(3) { "GET /api/stacks/5" }, requests)
    }

    @Test
    fun gitRedeployThatLeavesTheStackStoppedOrFailedIsNotDone() = runTest {
        statuses = mutableListOf(3, 4)
        statusMessage = "repository not found"
        val sessions = sessions()
        sessions.start(key, 5, StackOp.GitRedeploy(GitRedeployRequest("refs/heads/main", null, emptyList(), pullImage = false, prune = false)))
        assertEquals("repository not found", finished(sessions).error)
    }

    @Test
    fun gitSettingsSaveSettlesWithoutExpectingAStatus() = runTest {
        // Saving deploys nothing: a stopped (or failed) stack stays so, and that is a success.
        for (code in listOf(2, 4)) {
            requestLog.value = emptyList()
            statuses = mutableListOf(3, code)
            val sessions = sessions()
            sessions.start(key, 5, StackOp.GitSettings(gitSettings(password = null)))
            assertNull(finished(sessions).error, "status $code")
            assertEquals(listOf("GET /api/status", "POST /api/stacks/5/git?endpointId=1", "GET /api/stacks/5", "GET /api/stacks/5"), requests)
        }
    }

    @Test
    fun progressNeverHoldsTheGitPasswordAndErrorsBlankItOut() = runTest {
        opGate = CompletableDeferred()
        opErrorBody = """{"message":"authentication failed for s3cret-token"}"""
        val sessions = sessions()
        assertTrue(sessions.start(key, 5, StackOp.GitSettings(gitSettings(password = "s3cret-token"))))
        val running = assertIs<StackOp.GitSettings>(sessions.progress(key).value!!.op)
        assertNull(running.request.auth!!.password, "the running progress already has no password")
        assertEquals("deploy", running.request.auth!!.username)
        opGate!!.complete(Unit)
        val p = finished(sessions)
        assertNull(assertIs<StackOp.GitSettings>(p.op).request.auth!!.password)
        val error = assertNotNull(p.error)
        assertFalse(error.contains("s3cret-token"), error)
        assertTrue(error.contains("***"), error)
        assertFalse(p.toString().contains("s3cret-token"))
    }

    @Test
    fun detachIsDoneOnlyWhenTheStackIsNoLongerAGitStack() = runTest {
        gitConfig = gitJson
        statuses = mutableListOf(1, 3, 1)
        val sessions = sessions()
        assertTrue(sessions.start(key, 5, StackOp.Detach))
        assertNull(finished(sessions).error)
        assertTrue("PUT /api/stacks/5?endpointId=1" in requests)

        requestLog.value = emptyList()
        gitConfig = gitJson
        detachKeepsGit = true
        statuses = mutableListOf(1)
        sessions.clear(key)
        sessions.start(key, 5, StackOp.Detach)
        assertEquals("Portainer still reports the stack as deployed from git.", finished(sessions).error)
    }

    @Test
    fun startPostsWithTheEndpointThenConfirmsTheStackIsActive() = runTest {
        // Held so the running state is observed before the op can finish, however loaded the machine is.
        opGate = CompletableDeferred()
        val sessions = sessions()
        assertTrue(sessions.start(key, 5, StackOp.Start))
        assertEquals(StackProgress(StackOp.Start, running = true, error = null, stackId = 5), sessions.progress(key).value)
        settle { requests.isNotEmpty() }
        assertEquals(true, sessions.progress(key).value?.running)
        opGate!!.complete(Unit)
        assertEquals(StackProgress(StackOp.Start, running = false, error = null, stackId = 5), finished(sessions))
        assertEquals(listOf("POST /api/stacks/5/start?endpointId=1", "GET /api/stacks/5"), requests)
    }

    @Test
    fun asyncDeployIsPolledUntilItIsNoLongerDeploying() = runTest {
        statuses = mutableListOf(3, 3, 2)
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Stop)
        assertNull(finished(sessions).error)
        assertEquals(listOf("POST /api/stacks/5/stop?endpointId=1") + List(3) { "GET /api/stacks/5" }, requests)
    }

    @Test
    fun errorStatusFailsWithPortainersMessage() = runTest {
        statuses = mutableListOf(3, 4)
        statusMessage = "pull access denied for nginx"
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Start)
        assertEquals("pull access denied for nginx", finished(sessions).error)
    }

    @Test
    fun stillDeployingAfterTheTimeoutIsAnErrorNotASuccess() = runTest {
        statuses = mutableListOf(3)
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Start)
        assertEquals(STACK_STILL_WORKING_MESSAGE, finished(sessions).error)
    }

    @Test
    fun stopThatLeavesTheStackActiveIsNotReportedDone() = runTest {
        statuses = mutableListOf(1)
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Stop)
        assertNotNull(finished(sessions).error)
    }

    @Test
    fun updatePutsTheFileThenWaitsForTheAsyncDeploy() = runTest {
        statuses = mutableListOf(3, 3, 1)
        val sessions = sessions()
        val op = StackOp.Update("services: {}\n", listOf(EnvVar("A", "1")), pullImage = true, prune = true)
        assertTrue(sessions.start(key, 5, op))
        assertEquals(StackProgress(op, running = false, error = null, stackId = 5), finished(sessions))
        assertEquals(
            listOf("GET /api/status", "PUT /api/stacks/5?endpointId=1") + List(3) { "GET /api/stacks/5" },
            requests,
        )
    }

    @Test
    fun updateWhoseDeployFailsReportsPortainersMessage() = runTest {
        statuses = mutableListOf(3, 4)
        statusMessage = "manifest unknown"
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Update("a: b\n", emptyList(), pullImage = false, prune = false))
        assertEquals("manifest unknown", finished(sessions).error)
    }

    @Test
    fun deleteIsConfirmedByTheStackBeingGone() = runTest {
        statuses = mutableListOf(null)
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Delete)
        assertNull(finished(sessions).error)
        assertEquals(listOf("DELETE /api/stacks/5?endpointId=1", "GET /api/stacks/5"), requests)
    }

    @Test
    fun deleteOfAStackPortainerStillListsFails() = runTest {
        statuses = mutableListOf(2)
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Delete)
        assertNotNull(finished(sessions).error)
    }

    @Test
    fun networkFailureMidDeleteEndsWithAnErrorAndNothingClaimedDone() = runTest {
        opFailsWithNetworkError = true
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Delete)
        val p = finished(sessions)
        assertFalse(p.running)
        val error = assertNotNull(p.error)
        assertFalse(error.contains("secret-api-key"), error)
        assertFalse(sessions.isRunning("s1", 1))
    }

    @Test
    fun connectionLostWhileCheckingTheResultIsAnError() = runTest {
        pollFailsWithNetworkError = true
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Stop)
        val error = assertNotNull(finished(sessions).error)
        assertTrue(error.startsWith("Couldn't check the result"), error)
    }

    @Test
    fun secondOpForTheSameStackWhileRunningIsRefusedWithOneRequest() = runTest {
        opGate = CompletableDeferred()
        val sessions = sessions()
        assertTrue(sessions.start(key, 5, StackOp.Stop))
        settle { requests.isNotEmpty() }
        assertFalse(sessions.start(key, 5, StackOp.Delete))
        assertTrue(sessions.isRunning("s1", 1))
        assertFalse(sessions.isRunning("s1", 2))
        sessions.clear(key) // never forgets a running one
        assertEquals(StackOp.Stop, sessions.progress(key).value?.op)
        opGate!!.complete(Unit)
        statuses = mutableListOf(2)
        assertNull(finished(sessions).error)
        assertEquals(1, requests.count { !it.startsWith("GET") }, requests.toString())
        sessions.clear(key)
        assertNull(sessions.progress(key).value)
    }

    @Test
    fun finishedResultIsReplacedByANewOp() = runTest {
        statuses = mutableListOf(2)
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Start)
        assertNotNull(finished(sessions).error)
        statuses = mutableListOf(1)
        assertTrue(sessions.start(key, 5, StackOp.Start))
        assertNull(finished(sessions).error)
    }

    @Test
    fun dedicatedClientIsClosedWhenTheOpEnds() = runTest {
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Start)
        finished(sessions)
        val client = clients.value.single()
        assertFails { client.stack(5) }
    }

    @Test
    fun dedicatedClientIsClosedWhenTheOpFails() = runTest {
        opFailsWithNetworkError = true
        val sessions = sessions()
        sessions.start(key, 5, StackOp.Delete)
        assertNotNull(finished(sessions).error)
        val client = clients.value.single()
        opFailsWithNetworkError = false
        // A closed client fails before reaching the engine: no new request is recorded.
        val before = requests.size
        assertFails { client.stack(5) }
        assertEquals(before, requests.size)
    }

    @Test
    fun slowReadsCountTowardsTheTimeout() = runTest {
        statuses = mutableListOf(3)
        pollDelayMs = 30
        val sessions = StackSessions(
            scope,
            { PortainerClient("https://a.example.com", "k", MockEngine(handler)) },
            pollIntervalMs = 1,
            pollTimeoutMs = 60,
        )
        sessions.start(key, 5, StackOp.Start)
        assertEquals(STACK_STILL_WORKING_MESSAGE, finished(sessions).error)
        // ~60 ms of 30 ms reads: a handful, not the ~60 a wait-only count would allow.
        assertTrue(requests.count { it == "GET /api/stacks/5" } < 10, requests.toString())
    }

    @Test
    fun otherWorkOnTheEndpointRefusesWithoutARequest() = runTest {
        val sessions = sessions { _, endpointId -> "busy".takeIf { endpointId == 1 } }
        assertEquals("busy", sessions.refusal("s1", 1))
        assertFalse(sessions.start(key, 5, StackOp.Stop))
        assertNull(sessions.progress(key).value)
        assertTrue(requests.isEmpty())
        assertTrue(sessions.start(StackKey("s1", 2, "shop"), 5, StackOp.Start))
    }

    // ---- coordination through the AppContainer ----

    private suspend fun env(): TestEnv = TestEnv(handler).also { it.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k") }

    @Test
    fun runningStackOpRefusesRecreateCleanupAndEditorDeploy() = runTest {
        opGate = CompletableDeferred()
        val container = env().container
        assertTrue(container.stackSessions.start(key, 5, StackOp.Stop))
        assertTrue(container.stackSessions.isRunning("s1", 1))

        assertEquals(STACK_OPERATION_IN_PROGRESS_MESSAGE, container.recreateSessions.refusal("s1", 1))
        assertFalse(container.recreateSessions.start(SessionKey("s1", 1, "c2"), container.dedicatedClient("s1"), "c2", recreatePlan()))
        for (kind in CleanupKind.entries) {
            assertEquals(STACK_OPERATION_IN_PROGRESS_MESSAGE, container.cleanupSessions.refusal("s1", 1, kind))
            assertFalse(container.cleanupSessions.start(CleanupKey("s1", 1, kind), listOf(cleanupItem())))
        }
        val cleanup = CleanupViewModel(container, "s1", 1, CleanupKind.Images)
        cleanup.open()
        assertEquals(CleanupPhase.Error(STACK_OPERATION_IN_PROGRESS_MESSAGE), cleanup.phase.value)
        assertEquals(STACK_OPERATION_IN_PROGRESS_MESSAGE, container.deploySessions.refusal("s1", 1))
        assertFalse(container.deploySessions.start(DeployKey("s1", 1, "new", "web"), container.dedicatedClient("s1"), deployRequest()))
        // Other endpoints are unaffected.
        assertNull(container.recreateSessions.refusal("s1", 2))
        assertNull(container.deploySessions.refusal("s1", 2))
        assertTrue(requests.none { it.startsWith("DELETE /api/endpoints") || it.contains("/create") }, requests.toString())
    }

    @Test
    fun stackOpIsRefusedWhileACleanupOfAnyKindRuns() = runTest {
        val container = env().container
        assertTrue(container.cleanupSessions.start(CleanupKey("s1", 1, CleanupKind.Networks), listOf(cleanupItem(CleanupKind.Networks))))
        settle { requests.any { it.startsWith("DELETE") } }
        assertEquals(CLEANUP_IN_PROGRESS_MESSAGE, container.stackSessions.refusal("s1", 1))
        assertFalse(container.stackSessions.start(key, 5, StackOp.Start))
        assertTrue(requests.none { it.contains("/api/stacks") })
    }

    @Test
    fun stackOpIsRefusedWhileARecreateRuns() = runTest {
        val container = env().container
        assertTrue(container.recreateSessions.start(SessionKey("s1", 1, "c2"), container.dedicatedClient("s1"), "c2", recreatePlan()))
        assertEquals(RECREATE_IN_PROGRESS_MESSAGE, container.stackSessions.refusal("s1", 1))
        assertFalse(container.stackSessions.start(key, 5, StackOp.Stop))
    }

    @Test
    fun stackOpIsRefusedWhileAnEditorDeployRuns() = runTest {
        val container = env().container
        assertTrue(container.deploySessions.start(DeployKey("s1", 1, "new", "web"), container.dedicatedClient("s1"), deployRequest()))
        assertEquals(DEPLOY_IN_PROGRESS_MESSAGE, container.stackSessions.refusal("s1", 1))
        assertFalse(container.stackSessions.start(key, 5, StackOp.Delete))
    }

    private fun cleanupItem(kind: CleanupKind = CleanupKind.Containers) =
        CleanupItem(id = "x1", label = "x1-$kind", sizeBytes = null)

    private fun recreatePlan() = RecreatePlan(
        containerName = "old",
        wasRunning = false,
        createBody = JsonObject(emptyMap()),
        extraNetworks = emptyMap(),
        image = ImageRef.parse("busybox"),
        pull = true,
        registryId = null,
        changes = ChangeSummary(null, emptyList(), emptyList(), emptyList(), emptyList()),
        warnings = emptyList(),
    )

    private fun deployRequest() = DeployRequest(
        name = "web",
        body = JsonObject(emptyMap()),
        extraNetworks = emptyMap(),
        image = ImageRef.parse("nginx"),
        pull = false,
        registryId = null,
        start = false,
    )
}
