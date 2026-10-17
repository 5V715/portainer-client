package dev.silas.portainer.app.recreate

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.recreate.RecreateStep
import dev.silas.portainer.core.recreate.RecreateWarning
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecreateViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()
    private var image = "ghcr.io/acme/app:1"
    private var failCreate = false
    private var startGate: CompletableDeferred<Unit>? = null

    private fun inspect() = """{
      "Id":"abcdef1234567890","Name":"/web","Image":"sha256:old",
      "State":{"Status":"running","Running":true},
      "Config":{"Image":"$image","Env":["A=1","PATH=/x"],"Labels":{"com.docker.compose.project":"shop"}},
      "HostConfig":{"NetworkMode":"bridge"},
      "NetworkSettings":{"Networks":{"bridge":{"NetworkID":"n1"}}}
    }"""

    private val registries = """[
      {"Id":7,"Name":"GitHub","Type":8,"URL":"ghcr.io","Authentication":true,"Username":"me"},
      {"Id":3,"Name":"Hub","Type":6,"URL":"docker.io","Authentication":false}
    ]"""

    private suspend fun MockRequestHandleScope.handle(r: HttpRequestData): HttpResponseData {
        val path = r.url.encodedPath
        requests += "${r.method.value} ${path.substringAfter("/docker", path)}"
        return when {
            path.endsWith("/containers/abc/json") -> respond(inspect(), HttpStatusCode.OK, json)
            path.endsWith("/images/sha256:old/json") -> respond("""{"Config":{"Env":["PATH=/x"]}}""", HttpStatusCode.OK, json)
            path.endsWith("/api/registries") -> respond(registries, HttpStatusCode.OK, json)
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

    private suspend fun TestScope.editing(env: TestEnv): RecreateViewModel {
        val vm = RecreateViewModel(env.container, "s1", 1, "abc")
        settle { vm.state.value.phase != RecreatePhase.Loading }
        return vm
    }

    @Test
    fun protectedImageIsAnErrorAndReviewIsUnavailable() = runTest {
        image = "docker.io/portainer/portainer-ce:2.21"
        val vm = editing(env())
        assertEquals(RecreatePhase.Error(PROTECTED_IMAGE_MESSAGE), vm.state.value.phase)
        vm.review()
        assertEquals(RecreatePhase.Error(PROTECTED_IMAGE_MESSAGE), vm.state.value.phase)
    }

    @Test
    fun loadsFormAndPreselectsMatchingRegistry() = runTest {
        val vm = editing(env())
        val s = vm.state.value
        assertEquals(RecreatePhase.Editing, s.phase)
        assertEquals("web", s.containerName)
        assertEquals("ghcr.io/acme/app:1", s.image)
        assertEquals(listOf("A=1", "PATH=/x"), s.env)
        assertEquals(7, s.registryId)
        assertEquals(2, s.registries.size)
    }

    @Test
    fun registryFollowsImageUntilPickedManually() = runTest {
        val vm = editing(env())
        vm.setImage("nginx:1.27")
        assertEquals(3, vm.state.value.registryId)
        vm.setImage("quay.example.org/x/y")
        assertNull(vm.state.value.registryId)
        vm.setRegistry(7)
        vm.setImage("nginx:1.27")
        assertEquals(7, vm.state.value.registryId)
        vm.setRegistry(null)
        vm.setImage("ghcr.io/acme/app:2")
        assertNull(vm.state.value.registryId)
    }

    @Test
    fun reviewBuildsPlanWithAddedEnvAndWarnings() = runTest {
        val vm = editing(env())
        vm.addEnvRow()
        vm.setEnvRow(2, "B", "2")
        vm.setImage("ghcr.io/acme/app:2")
        vm.review()
        val phase = assertIs<RecreatePhase.Reviewing>(vm.state.value.phase)
        assertEquals(listOf("B=2"), phase.plan.changes.envAdded)
        assertEquals("ghcr.io/acme/app:1" to "ghcr.io/acme/app:2", phase.plan.changes.imageChange)
        assertTrue(RecreateWarning.StackMember("shop") in phase.plan.warnings)
        assertTrue(RecreateWarning.ConnectionDependency in phase.plan.warnings)
        assertEquals(7, phase.plan.registryId)
        vm.cancelReview()
        assertEquals(RecreatePhase.Editing, vm.state.value.phase)
        assertEquals("ghcr.io/acme/app:2", vm.state.value.image)
    }

    @Test
    fun validationErrors() = runTest {
        val vm = editing(env())
        vm.setEnv(listOf("A=1", "A=2"))
        vm.review()
        assertEquals(RecreatePhase.Editing, vm.state.value.phase)
        assertEquals("Duplicate variable: A", vm.state.value.validationError)

        vm.setEnv(listOf("A=1"))
        assertNull(vm.state.value.validationError)
        vm.setImage("  ")
        vm.review()
        assertEquals("Image is required", vm.state.value.validationError)

        vm.setImage("portainer/agent:2")
        vm.review()
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.validationError)
        assertEquals(RecreatePhase.Editing, vm.state.value.phase)
    }

    @Test
    fun emptyKeyRowsAreIgnored() = runTest {
        val vm = editing(env())
        vm.addEnvRow()
        vm.addEnvRow()
        vm.review()
        val phase = assertIs<RecreatePhase.Reviewing>(vm.state.value.phase)
        assertTrue(phase.plan.changes.envAdded.isEmpty())
    }

    @Test
    fun envRowEditsAndRemoval() = runTest {
        val vm = editing(env())
        vm.setEnv(listOf("FLAG", "A=1", "B=2"))
        vm.setEnvRow(0, "FLAG2", "")
        vm.setEnvRow(1, "A", "9")
        vm.removeEnvRow(2)
        assertEquals(listOf("FLAG2", "A=9"), vm.state.value.env)
    }

    @Test
    fun confirmRunsRecreatorAndMapsEventsToSteps() = runTest {
        val vm = editing(env())
        vm.setPull(false)
        vm.review()
        vm.confirm()
        settle { vm.state.value.phase is RecreatePhase.Done }
        val done = assertIs<RecreatePhase.Done>(vm.state.value.phase)
        assertEquals(RecreateResult.Success("new1"), done.result)
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
        val vm = editing(env())
        vm.setPull(false)
        vm.review()
        vm.confirm()
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
        val gate = CompletableDeferred<Unit>()
        startGate = gate
        val env = env()
        val first = editing(env)
        first.setPull(false)
        first.review()
        first.confirm()
        settle { first.state.value.phase.let { it is RecreatePhase.Running && it.steps.any { s -> s.status == StepStatus.Active } } }

        val inspectsBefore = requests.count { it.endsWith("/containers/abc/json") }
        val second = RecreateViewModel(env.container, "s1", 1, "abc")
        settle { second.state.value.phase is RecreatePhase.Running }
        assertEquals(StepStatus.Active, (second.state.value.phase as RecreatePhase.Running).steps.first { it.step == RecreateStep.StartNew }.status)
        assertEquals(inspectsBefore, requests.count { it.endsWith("/containers/abc/json") })

        gate.complete(Unit)
        settle { second.state.value.phase is RecreatePhase.Done && first.state.value.phase is RecreatePhase.Done }
        assertEquals(RecreateResult.Success("new1"), (second.state.value.phase as RecreatePhase.Done).result)

        // A third screen still sees the finished result until it is acknowledged.
        val third = RecreateViewModel(env.container, "s1", 1, "abc")
        settle { third.state.value.phase is RecreatePhase.Done }
        third.acknowledge()
        assertNull(env.container.recreateSessions.progress(SessionKey("s1", 1, "abc")).value)
    }

    @Test
    fun confirmOutsideReviewDoesNothing() = runTest {
        val vm = editing(env())
        vm.confirm()
        settle { true }
        assertEquals(RecreatePhase.Editing, vm.state.value.phase)
        assertTrue(requests.none { it.contains("rename") })
    }
}
