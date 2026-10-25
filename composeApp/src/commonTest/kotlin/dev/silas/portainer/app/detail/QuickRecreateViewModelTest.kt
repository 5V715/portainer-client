package dev.silas.portainer.app.detail

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.recreate.RecreateProgress
import dev.silas.portainer.app.recreate.RecreateResult
import dev.silas.portainer.core.recreate.RecreateStep
import dev.silas.portainer.app.recreate.SessionKey
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.recreate.RecreateWarning
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuickRecreateViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    // Appended from the engine's threads while the test reads it: an atomic snapshot list.
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private var createBody = ""
    private var image = "ghcr.io/acme/app:1"
    private var autoRemove = false
    private var createGate: CompletableDeferred<Unit>? = null

    private fun inspect() = """{
      "Id":"abcdef1234567890","Name":"/web","Image":"sha256:old",
      "State":{"Status":"running","Running":true},
      "Config":{"Image":"$image","Env":["A=1","PATH=/x"],"Labels":{"com.docker.compose.project":"shop"}},
      "HostConfig":{"NetworkMode":"bridge","AutoRemove":$autoRemove},
      "NetworkSettings":{"Networks":{"bridge":{"NetworkID":"n1"}}}
    }"""

    private val registries = """[
      {"Id":7,"Name":"GitHub","Type":8,"URL":"ghcr.io","Authentication":true,"Username":"me"}
    ]"""

    private suspend fun MockRequestHandleScope.handle(r: HttpRequestData): HttpResponseData {
        val path = r.url.encodedPath
        requestLog.update { it + "${r.method.value} ${path.substringAfter("/docker", path)}" }
        return when {
            path.endsWith("/containers/abc/json") -> respond(inspect(), HttpStatusCode.OK, json)
            path.endsWith("/images/sha256:old/json") -> respond("""{"Config":{"Env":["PATH=/x"]}}""", HttpStatusCode.OK, json)
            path.endsWith("/api/registries") -> respond(registries, HttpStatusCode.OK, json)
            path.endsWith("/containers/create") -> {
                createBody = (r.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                createGate?.await()
                respond("""{"Id":"new1"}""", HttpStatusCode.Created, json)
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

    private suspend fun TestScope.ready(env: TestEnv): QuickRecreateViewModel {
        val vm = QuickRecreateViewModel(env.container, "s1", 1, "abc")
        vm.open()
        settle { vm.state.value.phase == QuickPhase.Ready }
        return vm
    }

    @Test
    fun opensWithPullOnMatchedRegistryAndWarnings() = runTest {
        val vm = ready(env())
        val s = vm.state.value
        assertTrue(s.pull)
        assertEquals(7, s.registryId)
        assertEquals(RecreateWarning.StackMember("shop"), s.warnings.first())
    }

    @Test
    fun planUsesCurrentImageAndEnvAndPassesThePullSwitchThrough() = runTest {
        val env = env()
        val vm = ready(env)
        vm.confirm()
        settle { vm.state.value.phase == QuickPhase.Started }
        settle { env.container.recreateSessions.progress(SessionKey("s1", 1, "abc")).value?.result != null }
        assertTrue("POST /images/create" in requests.map { it.substringBefore('?') }, "pull on by default: $requests")
        val body = Json.parseToJsonElement(createBody).jsonObject
        assertEquals("ghcr.io/acme/app:1", body["Image"]!!.jsonPrimitive.content)
        // PATH=/x equals the image default, so the planner leaves it to the image: nothing is added, changed or removed.
        assertEquals(listOf("A=1"), body["Env"]!!.jsonArray.map { it.jsonPrimitive.content }, "no env diff against the inspected Env")
    }

    @Test
    fun pullSwitchOffSkipsThePull() = runTest {
        val env = env()
        val vm = ready(env)
        vm.setPull(false)
        vm.confirm()
        settle { env.container.recreateSessions.progress(SessionKey("s1", 1, "abc")).value?.result != null }
        assertTrue(requests.none { it.contains("/images/create") }, "$requests")
        val steps = env.container.recreateSessions.progress(SessionKey("s1", 1, "abc")).value!!.steps
        assertTrue(steps.none { it.step == RecreateStep.Pull })
    }

    @Test
    fun protectedContainerOpensNoSheetAndShowsTheMessage() = runTest {
        image = "docker.io/portainer/portainer-ce:2.21"
        val env = env()
        val vm = QuickRecreateViewModel(env.container, "s1", 1, "abc")
        vm.open()
        settle { vm.state.value.error != null }
        assertEquals(QuickPhase.Closed, vm.state.value.phase)
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
        vm.confirm()
        assertTrue(requests.none { it.contains("/containers/create") })
    }

    private fun TestScope.creates() = requests.count { it.contains("/containers/create") }
    private val key = SessionKey("s1", 1, "abc")

    @Test
    fun confirmStartsASessionOnADedicatedClientThatSurvivesServerDeletion() = runTest {
        val env = env()
        val gate = CompletableDeferred<Unit>().also { createGate = it }
        val vm = ready(env)
        vm.confirm()
        settle { vm.state.value.phase == QuickPhase.Started && creates() == 1 }
        env.container.deleteServer("s1") // while the create request is still in flight
        gate.complete(Unit)
        settle { env.container.recreateSessions.progress(key).value?.result != null }
        val progress: RecreateProgress = assertNotNull(env.container.recreateSessions.progress(key).value)
        assertEquals(RecreateResult.Success("new1"), progress.result)
    }

    @Test
    fun dismissIsRefusedWhileStartingSoTheRecreateStillRunsOnce() = runTest {
        val env = env()
        val vm = ready(env)
        vm.confirm()
        vm.dismiss() // before the start was reached
        assertEquals(QuickPhase.Ready, vm.state.value.phase)
        assertTrue(vm.state.value.starting)
        settle { vm.state.value.phase == QuickPhase.Started }
        settle { env.container.recreateSessions.progress(key).value?.result != null }
        assertEquals(1, creates())
        vm.dismiss()
        assertEquals(QuickPhase.Closed, vm.state.value.phase)
    }

    @Test
    fun changedWarningsAreShownAndNeedASecondTap() = runTest {
        val env = env()
        val vm = ready(env)
        autoRemove = true // changed outside the app after the sheet opened
        vm.confirm()
        settle { !vm.state.value.starting }
        assertEquals(QuickPhase.Ready, vm.state.value.phase)
        assertTrue(RecreateWarning.AutoRemove in vm.state.value.warnings)
        assertEquals(0, creates())
        vm.confirm()
        settle { vm.state.value.phase == QuickPhase.Started }
        settle { env.container.recreateSessions.progress(key).value?.result != null }
        assertEquals(1, creates())
    }

    @Test
    fun aFinishedEarlierResultIsClearedSoANewRecreateRuns() = runTest {
        val env = env()
        val first = ready(env)
        first.confirm()
        settle { env.container.recreateSessions.progress(key).value?.result != null }
        val second = ready(env)
        second.confirm()
        settle { second.state.value.phase == QuickPhase.Started }
        settle { env.container.recreateSessions.progress(key).value?.result != null }
        assertEquals(2, creates())
    }

    @Test
    fun aRecreateAlreadyInFlightIsJustShown() = runTest {
        val env = env()
        val gate = CompletableDeferred<Unit>().also { createGate = it }
        val first = ready(env)
        val second = ready(env)
        first.confirm()
        settle { creates() == 1 }
        second.confirm()
        settle { second.state.value.phase == QuickPhase.Started }
        gate.complete(Unit)
        settle { env.container.recreateSessions.progress(key).value?.result != null }
        assertEquals(1, creates())
    }
}
