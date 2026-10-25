package dev.silas.portainer.app.recreate

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.recreate.RecreateEdits
import dev.silas.portainer.core.recreate.RecreatePlanner
import dev.silas.portainer.core.recreate.RecreateStep
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecreateViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    // Appended from the engine's threads while the test reads it: an atomic snapshot list.
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private var failCreate = false
    private var startGate: CompletableDeferred<Unit>? = null

    private fun inspect() = """{
      "Id":"abcdef1234567890","Name":"/web","Image":"sha256:old",
      "State":{"Status":"running","Running":true},
      "Config":{"Image":"ghcr.io/acme/app:1","Env":["A=1","PATH=/x"],"Labels":{"com.docker.compose.project":"shop"}},
      "HostConfig":{"NetworkMode":"bridge"},
      "NetworkSettings":{"Networks":{"bridge":{"NetworkID":"n1"}}}
    }"""

    private suspend fun MockRequestHandleScope.handle(r: HttpRequestData): HttpResponseData {
        val path = r.url.encodedPath
        requestLog.update { it + "${r.method.value} ${path.substringAfter("/docker", path)}" }
        return when {
            path.endsWith("/containers/create") ->
                if (failCreate) respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json)
                else respond("""{"Id":"new1"}""", HttpStatusCode.Created, json)
            path.endsWith("/containers/new1/start") -> {
                startGate?.await()
                respond("", HttpStatusCode.NoContent)
            }
            r.method == HttpMethod.Post || r.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
            else -> respond("""{"message":"unexpected"}""", HttpStatusCode.NotFound, json)
        }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { handle(it) }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    private val key = SessionKey("s1", 1, "abc")

    private fun plan() = RecreatePlanner.plan(
        Json.parseToJsonElement(inspect()).jsonObject,
        null,
        RecreateEdits("ghcr.io/acme/app:1", listOf("A=1", "PATH=/x"), pullImage = false, registryId = null),
    )

    private suspend fun start(env: TestEnv): Boolean = env.container.recreateSessions.start(key, env.container.dedicatedClient("s1"), "abc", plan())

    private suspend fun TestScope.shown(env: TestEnv): RecreateViewModel {
        val vm = RecreateViewModel(env.container, "s1", 1, "abc")
        settle { vm.state.value.phase is RecreatePhase.Running || vm.state.value.phase is RecreatePhase.Done }
        return vm
    }

    /** Starts a recreate that stays suspended in StartNew until the returned gate completes. */
    private suspend fun TestScope.runningUntilGate(env: TestEnv): Pair<RecreateViewModel, CompletableDeferred<Unit>> {
        val gate = CompletableDeferred<Unit>()
        startGate = gate
        assertTrue(start(env))
        val vm = RecreateViewModel(env.container, "s1", 1, "abc")
        settle {
            vm.state.value.phase.let { p -> p is RecreatePhase.Running && p.steps.any { it.step == RecreateStep.StartNew && it.status == StepStatus.Active } }
        }
        return vm to gate
    }

    @Test
    fun withoutARecreateThereIsNothingToShow() = runTest {
        val vm = RecreateViewModel(env().container, "s1", 1, "abc")
        settle { true }
        assertEquals(RecreatePhase.Error(NOTHING_RUNNING_MESSAGE), vm.state.value.phase)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun savingTheServerMidRecreateDoesNotBreakIt() = runTest {
        val env = env()
        val (vm, gate) = runningUntilGate(env)
        env.container.saveServer(ServerConfig("s1", "Renamed", "https://a.example.com"), null)
        gate.complete(Unit)
        settle { vm.state.value.phase is RecreatePhase.Done }
        assertEquals(RecreateResult.Success("new1"), (vm.state.value.phase as RecreatePhase.Done).result)
        assertTrue(requests.last().startsWith("DELETE /containers/abc"), "old container removed after the new one started")
    }

    @Test
    fun deletingTheServerMidRecreateDoesNotBreakIt() = runTest {
        val env = env()
        val (vm, gate) = runningUntilGate(env)
        env.container.deleteServer("s1")
        gate.complete(Unit)
        settle { vm.state.value.phase is RecreatePhase.Done }
        assertEquals(RecreateResult.Success("new1"), (vm.state.value.phase as RecreatePhase.Done).result)
    }

    @Test
    fun showsTheStepsOfTheRunningRecreate() = runTest {
        val env = env()
        assertTrue(start(env))
        val vm = shown(env)
        settle { vm.state.value.phase is RecreatePhase.Done }
        val done = assertIs<RecreatePhase.Done>(vm.state.value.phase)
        assertEquals(RecreateResult.Success("new1"), done.result)
        assertEquals("web", vm.state.value.containerName)
        assertEquals(
            listOf(RecreateStep.RenameOld, RecreateStep.CreateNew, RecreateStep.StopOld, RecreateStep.StartNew, RecreateStep.RemoveOld),
            done.steps.map { it.step },
        )
        assertTrue(done.steps.all { it.status == StepStatus.Done })
        assertTrue("POST /containers/new1/start" in requests)
    }

    @Test
    fun failureCarriesStepMessageAndRollbackState() = runTest {
        failCreate = true
        val env = env()
        assertTrue(start(env))
        val vm = shown(env)
        settle { vm.state.value.phase is RecreatePhase.Done }
        val done = assertIs<RecreatePhase.Done>(vm.state.value.phase)
        val failure = assertIs<RecreateResult.Failure>(done.result)
        assertEquals(RecreateStep.CreateNew, failure.step)
        assertTrue(failure.rolledBack)
        assertTrue(failure.message.contains("boom"))
        assertEquals(StepStatus.Failed, done.steps.first { it.step == RecreateStep.CreateNew }.status)
        assertEquals(StepStatus.Pending, done.steps.first { it.step == RecreateStep.StartNew }.status)
        assertEquals("Your original container was restored.", rollbackText(failure.rolledBack))
    }

    @Test
    fun progressSurvivesViewModelRecreation() = runTest {
        val env = env()
        val (first, gate) = runningUntilGate(env)

        val second = RecreateViewModel(env.container, "s1", 1, "abc")
        settle {
            second.state.value.phase.let { p -> p is RecreatePhase.Running && p.steps.any { it.step == RecreateStep.StartNew && it.status == StepStatus.Active } }
        }

        gate.complete(Unit)
        settle { second.state.value.phase is RecreatePhase.Done && first.state.value.phase is RecreatePhase.Done }
        assertEquals(RecreateResult.Success("new1"), (second.state.value.phase as RecreatePhase.Done).result)

        // A third screen still sees the finished result until it is acknowledged.
        val third = RecreateViewModel(env.container, "s1", 1, "abc")
        settle { third.state.value.phase is RecreatePhase.Done }
        third.acknowledge()
        assertNull(env.container.recreateSessions.progress(key).value)
        // The screen keeps showing the result it acted on.
        settle { true }
        assertIs<RecreatePhase.Done>(third.state.value.phase)
    }

    @Test
    fun aSecondStartForTheSameContainerIsRefused() = runTest {
        val env = env()
        val (_, gate) = runningUntilGate(env)
        assertFalse(start(env))
        gate.complete(Unit)
    }
}
