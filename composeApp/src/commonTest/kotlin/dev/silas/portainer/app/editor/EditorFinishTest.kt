package dev.silas.portainer.app.editor

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.cleanup.CleanupKey
import dev.silas.portainer.app.recreate.CLEANUP_IN_PROGRESS_MESSAGE
import dev.silas.portainer.app.recreate.SessionKey
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.cleanup.CleanupItem
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.core.editor.Conflict
import dev.silas.portainer.core.model.ImageRef
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorFinishTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private val createLog = MutableStateFlow(emptyList<Pair<String, String>>())
    private val creates: List<Pair<String, String>> get() = createLog.value
    private var image = "ghcr.io/acme/app:1"
    private var ports = """{"80/tcp":[{"HostIp":"","HostPort":"8080"}]}"""
    private var failCreate = false
    private var failStart = false
    private var cleanupGate: CompletableDeferred<Unit>? = null
    private var createGate: CompletableDeferred<Unit>? = null
    private var listedName = "db"
    private var running = true
    private var labels = "{}"
    private var registries = "[]"
    private val pullAuth = MutableStateFlow(emptyList<String?>())

    private val fullId = "abcdef1234567890aaaa"

    private fun inspect() = """{
      "Id":"$fullId","Name":"/web","Image":"sha256:old",
      "State":{"Status":"${if (running) "running" else "exited"}","Running":$running},
      "Config":{"Image":"$image","Hostname":"abcdef123456","Env":["A=1"],"ExposedPorts":{"80/tcp":{}},"Labels":$labels},
      "HostConfig":{"NetworkMode":"front","PortBindings":$ports,"Binds":["data:/data"],"RestartPolicy":{"Name":"always","MaximumRetryCount":0}},
      "Mounts":[{"Type":"volume","Name":"data","Destination":"/data"}],
      "NetworkSettings":{"Networks":{
        "front":{"NetworkID":"n2","Aliases":["web","abcdef123456"],"IPAMConfig":{"IPv4Address":"172.20.0.5"},"MacAddress":"02:42:ac:14:00:05"}
      }}
    }"""

    private fun list() = """[
      {"Id":"$fullId","Names":["/web"],"Image":"$image","State":"${if (running) "running" else "exited"}","Status":"Up",
       "Mounts":[{"Type":"volume","Name":"data"}],"NetworkSettings":{"Networks":{"front":{}}},
       "Ports":${if (running) """[{"IP":"0.0.0.0","PrivatePort":80,"PublicPort":8080,"Type":"tcp"}]""" else "[]"}},
      {"Id":"ddd000","Names":["/$listedName"],"Image":"postgres","State":"exited","Status":"Exited",
       "Mounts":[{"Type":"volume","Name":"data"}]},
      {"Id":"ccc000","Names":["/cache"],"Image":"redis","State":"running","Status":"Up",
       "Ports":[{"IP":"0.0.0.0","PrivatePort":6379,"PublicPort":7000,"Type":"tcp"}]}
    ]"""

    private val stoppedInspect = """{"Id":"ddd000","Name":"/db","HostConfig":{"PortBindings":{"5432/tcp":[{"HostIp":"","HostPort":"5432"}]}},
      "NetworkSettings":{"Networks":{"front":{"NetworkID":"n2","IPAMConfig":{"IPv4Address":"172.20.0.9"},"IPAddress":""}}}}"""

    // A network inspect lists running endpoints only.
    private fun frontNetwork() = """{"Id":"n2","Name":"front","Driver":"bridge",
      "Containers":${if (running) """{"$fullId":{"Name":"web","IPv4Address":"172.20.0.5/16"}}""" else "{}"}}"""

    private suspend fun MockRequestHandleScope.handle(r: HttpRequestData): HttpResponseData {
        val path = r.url.encodedPath
        val short = path.substringAfter("/docker", path)
        requestLog.update { it + "${r.method.value} $short" }
        return when {
            path.endsWith("/containers/abc/json") || path.endsWith("/containers/$fullId/json") -> respond(inspect(), HttpStatusCode.OK, json)
            path.endsWith("/containers/ddd000/json") -> respond(stoppedInspect, HttpStatusCode.OK, json)
            path.endsWith("/containers/json") -> respond(list(), HttpStatusCode.OK, json)
            path.endsWith("/images/sha256:old/json") -> respond("""{"Config":{"Env":[]}}""", HttpStatusCode.OK, json)
            path.endsWith("/api/registries") -> respond(registries, HttpStatusCode.OK, json)
            path.endsWith("/volumes") -> respond("""{"Volumes":[{"Name":"data","Driver":"local"}]}""", HttpStatusCode.OK, json)
            path.endsWith("/networks") -> respond("""[{"Id":"n2","Name":"front","Driver":"bridge"}]""", HttpStatusCode.OK, json)
            path.endsWith("/networks/front") -> respond(frontNetwork(), HttpStatusCode.OK, json)
            path.endsWith("/images/create") -> {
                pullAuth.update { it + r.headers["X-Registry-Auth"] }
                respond("""{"status":"Downloading","id":"l1","progress":"[==>  ] 1MB/2MB"}""", HttpStatusCode.OK, json)
            }
            path.endsWith("/containers/create") -> {
                val body = (r.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                createLog.update { it + (r.url.parameters["name"].orEmpty() to body) }
                createGate?.await()
                if (failCreate) respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json)
                else respond("""{"Id":"new1"}""", HttpStatusCode.Created, json)
            }
            path.endsWith("/containers/new1/start") ->
                if (failStart) respond("""{"message":"port is already allocated"}""", HttpStatusCode.InternalServerError, json)
                else respond("", HttpStatusCode.NoContent)
            path.endsWith("/containers/zzz") && r.method == HttpMethod.Delete -> {
                cleanupGate?.await()
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

    private suspend fun TestScope.loaded(env: TestEnv, containerId: String? = "abc"): EditorViewModel {
        val vm = EditorViewModel(env.container, "s1", 1, containerId)
        settle { vm.state.value.phase != EditorPhase.Loading }
        return vm
    }

    private suspend fun TestScope.reviewing(vm: EditorViewModel, action: FinishAction): FinishPhase.Review {
        assertTrue(vm.finish(action))
        settle { vm.state.value.finish !is FinishPhase.Checking }
        return assertIs<FinishPhase.Review>(vm.state.value.finish)
    }

    private fun body(i: Int = 0): JsonObject = Json.parseToJsonElement(creates[i].second).jsonObject

    /** Requests that touch the original container other than reading its inspect. */
    private fun touchesOriginal() = requests.filter {
        (it.contains("/containers/abc") || it.contains("/containers/$fullId")) && !(it.startsWith("GET") && it.endsWith("/json"))
    }

    // ---- Deploy as copy ----

    @Test
    fun copyNeverCallsAnyApiOnTheOriginalContainer() = runTest {
        val vm = loaded(env())
        // Move the copy off the original's port and IP so nothing blocks it.
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(ipv4 = ""))
        val review = reviewing(vm, FinishAction.Copy)
        assertEquals("web-copy", review.name)
        assertTrue(review.canConfirm, "review: $review")
        vm.confirm()
        settle { vm.state.value.navigation != null }
        assertEquals(EditorNavigation.OpenContainer("new1"), vm.state.value.navigation)

        assertEquals("web-copy", creates.single().first)
        assertTrue(touchesOriginal().isEmpty(), "original touched: ${touchesOriginal()}")
        assertTrue(requests.none { it.contains("rename") || it.contains("/stop") })
        assertTrue("POST /containers/new1/start" in requests, "the original was running, so the copy is started")
        // The copy doesn't take the original's hostname, MAC or generated alias.
        val created = body()
        assertNull(created["Hostname"])
        val endpoint = created.getValue("NetworkingConfig").jsonObject.getValue("EndpointsConfig").jsonObject.getValue("front").jsonObject
        assertNull(endpoint["MacAddress"])
    }

    @Test
    fun copyReviewListsConflictsAndABlockingOneDisablesDeploy() = runTest {
        val vm = loaded(env())
        val review = reviewing(vm, FinishAction.Copy)
        // The original still runs on 8080 and holds 172.20.0.5: both block; the shared volume is a warning.
        assertTrue(review.conflicts.any { it is Conflict.HostPortInUse && it.running && it.byContainer == "web" }, "${review.conflicts}")
        assertTrue(review.conflicts.any { it is Conflict.FixedIpInUse && it.ip == "172.20.0.5" }, "${review.conflicts}")
        assertTrue(review.conflicts.contains(Conflict.SharedNamedVolume("data")))
        assertTrue(review.warnings.any { it == "Both containers will use volume data." }, "${review.warnings}")
        assertFalse(review.canConfirm)

        vm.confirm()
        advanceUntilIdle()
        settle { true }
        assertTrue(creates.isEmpty())
        assertIs<FinishPhase.Review>(vm.state.value.finish)
    }

    @Test
    fun copyNameTakenBlocksUntilChanged() = runTest {
        listedName = "web-copy"
        val vm = loaded(env())
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(ipv4 = ""))
        val review = reviewing(vm, FinishAction.Copy)
        assertTrue(Conflict.NameTaken("web-copy") in review.conflicts)
        assertFalse(review.canConfirm)

        vm.setDeployName("web-2")
        val renamed = assertIs<FinishPhase.Review>(vm.state.value.finish)
        assertEquals("web-2", renamed.name)
        assertTrue(renamed.conflicts.none { it is Conflict.NameTaken })
        assertTrue(renamed.canConfirm)

        vm.setDeployName("-bad")
        assertNotNull(assertIs<FinishPhase.Review>(vm.state.value.finish).nameError)
        assertFalse(assertIs<FinishPhase.Review>(vm.state.value.finish).canConfirm)
    }

    @Test
    fun copyOfAStoppedOriginalWithAFixedIpIsBlockedUntilTheIpChanges() = runTest {
        running = false
        val vm = loaded(env())
        val review = reviewing(vm, FinishAction.Copy)
        assertTrue(Conflict.FixedIpInUse("front", "172.20.0.5", "web") in review.blocking, "${review.conflicts}")
        assertFalse(review.canConfirm)

        vm.backToForm()
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(ipv4 = "172.20.0.99"))
        val again = reviewing(vm, FinishAction.Copy)
        assertTrue(again.conflicts.none { it is Conflict.FixedIpInUse }, "${again.conflicts}")
        assertTrue(again.blocking.isEmpty(), "${again.blocking}")
        assertTrue(again.canConfirm)
    }

    @Test
    fun protectedContainerCopyIsConfirmableOnceItsPortAndIpMoveAndReplaceStaysRefused() = runTest {
        image = "portainer/portainer-ce:2.21"
        val vm = loaded(env())
        assertNotNull(vm.state.value.protectedMessage)
        assertFalse(vm.state.value.canReplace)
        assertFalse(vm.finish(FinishAction.Replace))
        assertNull(vm.state.value.finish)

        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(ipv4 = "172.20.0.99"))
        val review = reviewing(vm, FinishAction.Copy)
        assertEquals("web-copy", review.name)
        assertNull(review.blocked)
        assertTrue(review.blocking.isEmpty(), "${review.blocking}")
        assertTrue(review.canConfirm)
        vm.confirm()
        settle { vm.state.value.navigation != null }
        assertTrue(touchesOriginal().isEmpty())
    }

    @Test
    fun createFailureRemovesNothing() = runTest {
        failCreate = true
        val vm = loaded(env())
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(ipv4 = ""))
        reviewing(vm, FinishAction.Copy)
        vm.confirm()
        settle { (vm.state.value.finish as? FinishPhase.Deploying)?.progress?.result != null }
        val failure = assertIs<DeployResult.Failure>((vm.state.value.finish as FinishPhase.Deploying).progress.result)
        assertEquals(DeployStep.Create, failure.step)
        assertNull(failure.createdId)
        assertTrue(failure.message.contains("boom"))
        assertTrue(requests.none { it.startsWith("DELETE") })
        assertTrue(touchesOriginal().isEmpty())
    }

    @Test
    fun failureAfterCreateLeavesTheCreatedContainerAndSaysSo() = runTest {
        failStart = true
        val vm = loaded(env())
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(ipv4 = ""))
        reviewing(vm, FinishAction.Copy)
        vm.confirm()
        settle { (vm.state.value.finish as? FinishPhase.Deploying)?.progress?.result != null }
        val progress = (vm.state.value.finish as FinishPhase.Deploying).progress
        val failure = assertIs<DeployResult.Failure>(progress.result)
        assertEquals(DeployStep.Start, failure.step)
        assertEquals("new1", failure.createdId)
        assertTrue(deployFailureText(progress).contains("web-copy"), deployFailureText(progress))
        assertTrue(deployFailureText(progress).contains("was created"), deployFailureText(progress))
        assertTrue(requests.none { it.startsWith("DELETE") })
        assertTrue(touchesOriginal().isEmpty())
    }

    @Test
    fun deploySurvivesTheViewModelAndRefusesASecondRunForTheSameName() = runTest {
        val env = env()
        val sessions = env.container.deploySessions
        val key = DeployKey("s1", 1, NEW_SOURCE, "x")
        val gate = CompletableDeferred<Unit>()
        createGate = gate
        val request = DeployRequest(
            name = "x", body = JsonObject(emptyMap()), extraNetworks = emptyMap(),
            image = ImageRef.parse("busybox"), pull = false, registryId = null, start = false,
        )
        assertTrue(sessions.start(key, env.container.dedicatedClient("s1"), request))
        settle { creates.isNotEmpty() }
        assertFalse(sessions.start(key, env.container.dedicatedClient("s1"), request))
        // Saving the server doesn't cut the deploy's own client off.
        env.container.saveServer(ServerConfig("s1", "Renamed", "https://a.example.com"), null)
        gate.complete(Unit)
        settle { sessions.progress(key).value?.result != null }
        assertEquals(DeployResult.Success("new1"), sessions.progress(key).value?.result)
        assertEquals(1, creates.size)
        assertEquals(listOf(DeployStep.Create), sessions.progress(key).value?.steps?.map { it.step })
    }

    // ---- Create ----

    @Test
    fun newWithStartOffDoesNotCallStart() = runTest {
        val vm = loaded(env(), containerId = null)
        vm.setImage("nginx:1")
        vm.setName("fresh")
        vm.setStartAfterCreate(false)
        val review = reviewing(vm, FinishAction.Create)
        assertEquals("fresh", review.name)
        assertFalse(review.start)
        assertTrue(review.canConfirm, "$review")
        vm.confirm()
        settle { vm.state.value.navigation != null }
        assertEquals(EditorNavigation.OpenContainer("new1"), vm.state.value.navigation)
        assertEquals("fresh", creates.single().first)
        assertEquals("nginx:1", body()["Image"]?.jsonPrimitive?.content)
        assertTrue(requests.none { it.endsWith("/start") }, "$requests")
        assertTrue(requests.any { it.startsWith("POST /images/create") }, "New pulls by default")
    }

    @Test
    fun newWithStartOnStartsAndBlocksAPortHeldByARunningContainer() = runTest {
        val vm = loaded(env(), containerId = null)
        vm.setImage("nginx:1")
        vm.addPort()
        vm.updatePort(0, PortDraft("", "8080", "80", "tcp"))
        val review = reviewing(vm, FinishAction.Create)
        assertTrue(review.start)
        assertTrue(review.conflicts.any { it is Conflict.HostPortInUse && it.running })
        assertFalse(review.canConfirm)

        vm.backToForm()
        assertNull(vm.state.value.finish)
        vm.updatePort(0, PortDraft("", "5432", "80", "tcp"))
        val again = reviewing(vm, FinishAction.Create)
        // A stopped holder (found by inspecting it) only warns.
        assertTrue(again.conflicts.any { it is Conflict.HostPortInUse && !it.running && it.byContainer == "db" }, "${again.conflicts}")
        assertTrue(again.canConfirm)
        vm.confirm()
        settle { vm.state.value.navigation != null }
        assertTrue("POST /containers/new1/start" in requests)
    }

    @Test
    fun newWithAnIpHeldByAStoppedContainerIsBlocked() = runTest {
        val vm = loaded(env(), containerId = null)
        vm.setImage("nginx:1")
        vm.addNetwork()
        val last = vm.state.value.draft.networks.lastIndex
        vm.updateNetwork(last, NetworkDraft("front", "", "172.20.0.9", "", ""))
        val review = reviewing(vm, FinishAction.Create)
        assertTrue(Conflict.FixedIpInUse("front", "172.20.0.9", "db") in review.blocking, "${review.conflicts}")
        assertFalse(review.canConfirm)
    }

    // ---- Replace ----

    @Test
    fun replaceUsesRecreateSessionsAndThePlanIncludesThePortChange() = runTest {
        val env = env()
        val vm = loaded(env)
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        val review = reviewing(vm, FinishAction.Replace)
        assertTrue(review.changes.any { it.startsWith("Ports: added") && it.contains("9090") }, "${review.changes}")
        assertTrue(review.changes.any { it.startsWith("Ports: removed") && it.contains("8080") }, "${review.changes}")
        // Its own port, IP and volume aren't conflicts with itself.
        assertTrue(review.conflicts.isEmpty(), "${review.conflicts}")
        assertTrue(review.canConfirm)

        vm.confirm()
        settle { vm.state.value.navigation != null }
        assertEquals(EditorNavigation.ReplaceStarted, vm.state.value.navigation)
        val progress = env.container.recreateSessions.progress(SessionKey("s1", 1, "abc"))
        settle { progress.value?.result != null }
        val created = body()
        val bindings = created.getValue("HostConfig").jsonObject.getValue("PortBindings").jsonObject
        assertEquals("9090", bindings.getValue("80/tcp").jsonArray[0].jsonObject.getValue("HostPort").jsonPrimitive.content)
        assertTrue(requests.any { it.contains("/containers/abc/rename") })
    }

    @Test
    fun replaceIsRefusedWhileAContainersCleanupRuns() = runTest {
        val env = env()
        val gate = CompletableDeferred<Unit>()
        cleanupGate = gate
        val cleanupKey = CleanupKey("s1", 1, CleanupKind.Containers)
        assertTrue(env.container.cleanupSessions.start(cleanupKey, listOf(CleanupItem("zzz", "old", null))))
        settle { requests.any { it.startsWith("DELETE /containers/zzz") } }

        val vm = loaded(env)
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        val review = reviewing(vm, FinishAction.Replace)
        assertEquals(CLEANUP_IN_PROGRESS_MESSAGE, review.blocked)
        assertFalse(review.canConfirm)
        vm.confirm()
        advanceUntilIdle()
        assertNull(env.container.recreateSessions.progress(SessionKey("s1", 1, "abc")).value)
        assertTrue(requests.none { it.contains("rename") })

        // The session itself refuses too, whoever calls it.
        assertNotNull(env.container.recreateSessions.refusal("s1", 1))
        gate.complete(Unit)
        settle { env.container.cleanupSessions.phase(cleanupKey).value is dev.silas.portainer.app.cleanup.CleanupPhase.Done }
        assertNull(env.container.recreateSessions.refusal("s1", 1))
    }

    @Test
    fun replaceBlocksWhenTheContainerChangedInAnEditedSection() = runTest {
        val vm = loaded(env())
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        ports = """{"80/tcp":[{"HostIp":"","HostPort":"7070"}]}"""
        val review = reviewing(vm, FinishAction.Replace)
        assertNotNull(review.blocked)
        assertTrue(review.blocked.contains("Ports"), review.blocked)
        assertFalse(review.canConfirm)
    }

    @Test
    fun replaceShowsANoticeWhenTheContainerChangedElsewhere() = runTest {
        val vm = loaded(env())
        vm.setRestartPolicy("unless-stopped")
        ports = """{"80/tcp":[{"HostIp":"","HostPort":"7070"}]}"""
        val review = reviewing(vm, FinishAction.Replace)
        assertNull(review.blocked)
        assertEquals(CONTAINER_CHANGED_NOTICE, review.notice)
        // Built from the fresh inspect: the outside change is kept, the user's edit applied.
        vm.confirm()
        settle { creates.isNotEmpty() }
        val host = body().getValue("HostConfig").jsonObject
        assertEquals("7070", host.getValue("PortBindings").jsonObject.getValue("80/tcp").jsonArray[0].jsonObject.getValue("HostPort").jsonPrimitive.content)
        assertEquals("unless-stopped", host.getValue("RestartPolicy").jsonObject.getValue("Name").jsonPrimitive.content)
    }

    @Test
    fun formEditsAreIgnoredWhileReviewing() = runTest {
        val vm = loaded(env())
        reviewing(vm, FinishAction.Copy)
        vm.setName("other")
        assertEquals("web", vm.state.value.draft.name)
        vm.backToForm()
        vm.setName("other")
        assertEquals("other", vm.state.value.draft.name)
    }

    // ---- fix round 1 ----

    /** Starts a copy that moved off the original's port and IP; returns its view model. */
    private suspend fun TestScope.copyStarted(env: TestEnv): EditorViewModel {
        val vm = loaded(env)
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(ipv4 = ""))
        reviewing(vm, FinishAction.Copy)
        vm.confirm()
        settle { vm.state.value.finish is FinishPhase.Deploying }
        return vm
    }

    @Test
    fun aDeployThatFailsAfterTheUserLeftIsShownWhenTheEditorOpensAgain() = runTest {
        val env = env()
        failStart = true
        val gate = CompletableDeferred<Unit>().also { createGate = it }
        copyStarted(env) // the user leaves while Create is in flight
        gate.complete(Unit)
        settle { env.container.deploySessions.find("s1", 1, "abc")?.let { env.container.deploySessions.progress(it).value?.result } != null }

        val again = EditorViewModel(env.container, "s1", 1, "abc")
        settle { again.state.value.phase == EditorPhase.Ready }
        val shown = assertIs<FinishPhase.Deploying>(again.state.value.finish)
        val failure = assertIs<DeployResult.Failure>(shown.progress.result)
        assertEquals("new1", failure.createdId)
        assertTrue(deployFailureText(shown.progress).contains("was created"))
        assertNull(again.state.value.navigation)

        // Acknowledging it goes back to the form and forgets it; a New editor never sees a copy's deploy.
        again.backToForm()
        assertNull(again.state.value.finish)
        assertNull(env.container.deploySessions.find("s1", 1, "abc"))
        val other = EditorViewModel(env.container, "s1", 1, null)
        settle { other.state.value.phase == EditorPhase.Ready }
        assertNull(other.state.value.finish)
    }

    @Test
    fun aRunningDeployIsShownAgainAndOpensItsContainerWhenDone() = runTest {
        val env = env()
        val gate = CompletableDeferred<Unit>().also { createGate = it }
        copyStarted(env)
        val again = EditorViewModel(env.container, "s1", 1, "abc")
        settle { (again.state.value.finish as? FinishPhase.Deploying)?.progress?.result == null && again.state.value.finish != null }
        gate.complete(Unit)
        settle { again.state.value.navigation != null }
        assertEquals(EditorNavigation.OpenContainer("new1"), again.state.value.navigation)
        again.navigated()
        assertNull(env.container.deploySessions.find("s1", 1, "abc"))
    }

    @Test
    fun backOnAFailedDeployGoesToTheFormWithTheEditsKept() = runTest {
        failStart = true
        val vm = copyStarted(env())
        settle { (vm.state.value.finish as? FinishPhase.Deploying)?.progress?.result != null }
        vm.backToForm()
        assertNull(vm.state.value.finish)
        assertEquals("9090", vm.state.value.form.ports[0].hostPort)
        assertTrue(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun backOnAFinishedDeployFoundLaterOpensTheContainer() = runTest {
        val env = env()
        val gate = CompletableDeferred<Unit>().also { createGate = it }
        val first = copyStarted(env)
        // Simulate leaving: the first editor stops listening, the deploy finishes without anyone watching.
        first.viewModelScope.cancel()
        gate.complete(Unit)
        settle { env.container.deploySessions.find("s1", 1, "abc")?.let { env.container.deploySessions.progress(it).value?.result } != null }
        val again = EditorViewModel(env.container, "s1", 1, "abc")
        settle { (again.state.value.finish as? FinishPhase.Deploying)?.progress?.result != null }
        assertNull(again.state.value.navigation, "a result found later waits for the user")
        again.backToForm()
        assertEquals(EditorNavigation.OpenContainer("new1"), again.state.value.navigation)
    }

    @Test
    fun replaceBlocksAPortHeldByARunningContainerWhenTheOriginalRuns() = runTest {
        val vm = loaded(env())
        vm.updatePort(0, PortDraft("", "7000", "80", "tcp"))
        val review = reviewing(vm, FinishAction.Replace)
        assertTrue(review.blocking.any { it is Conflict.HostPortInUse && it.byContainer == "cache" }, "${review.blocking}")
        assertFalse(review.canConfirm)
    }

    @Test
    fun replaceOnlyWarnsAboutAStoppedHoldersPort() = runTest {
        val vm = loaded(env())
        vm.updatePort(0, PortDraft("", "5432", "80", "tcp"))
        val review = reviewing(vm, FinishAction.Replace)
        assertTrue(review.blocking.isEmpty())
        assertTrue(review.warnings.any { it.contains("stopped container db") }, "${review.warnings}")
    }

    @Test
    fun reloadCurrentSettingsKeepsEditsOfSectionsThatDidNotChangeOutside() = runTest {
        val vm = loaded(env())
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        vm.setRestartPolicy("unless-stopped")
        ports = """{"80/tcp":[{"HostIp":"","HostPort":"7070"}]}"""
        val review = reviewing(vm, FinishAction.Replace)
        assertTrue(review.blocked!!.contains("will be lost"), review.blocked)
        assertEquals(setOf(dev.silas.portainer.core.editor.FormField.Ports), review.stale)

        vm.reloadCurrent()
        val s = vm.state.value
        assertNull(s.finish)
        assertEquals("7070", s.form.ports[0].hostPort, "the port edit is replaced by the current setting")
        assertEquals("7070", s.before.ports[0].hostPort)
        assertEquals("unless-stopped", s.form.restart.name, "the restart edit is kept")
        assertEquals(setOf(dev.silas.portainer.core.editor.FormField.Restart), s.dirty)

        val again = reviewing(vm, FinishAction.Replace)
        assertNull(again.blocked)
        assertTrue(again.canConfirm)
    }

    @Test
    fun copyStartSwitchDefaultsToTheOriginalsStateAndCanBeTurnedOff() = runTest {
        val vm = loaded(env())
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(ipv4 = ""))
        val review = reviewing(vm, FinishAction.Copy)
        assertTrue(review.startEditable)
        assertTrue(review.start)
        vm.setDeployStart(false)
        assertFalse(assertIs<FinishPhase.Review>(vm.state.value.finish).start)
        vm.confirm()
        settle { vm.state.value.navigation != null }
        assertTrue(requests.none { it.endsWith("/start") }, "$requests")
    }

    @Test
    fun copyPullUsesTheChosenRegistryAndShowsPullProgress() = runTest {
        registries = """[{"Id":7,"Name":"GitHub","Type":8,"URL":"ghcr.io","Authentication":true,"Username":"me"}]"""
        val env = env()
        val vm = loaded(env)
        vm.updatePort(0, PortDraft("", "9090", "80", "tcp"))
        vm.updateNetwork(0, vm.state.value.draft.networks[0].copy(ipv4 = ""))
        vm.setPull(true)
        vm.setRegistry(7)
        reviewing(vm, FinishAction.Copy)
        vm.confirm()
        settle { vm.state.value.navigation != null }
        assertEquals(listOf<String?>(dev.silas.portainer.core.registry.registryAuthHeader(7)), pullAuth.value)
        val key = assertNotNull(env.container.deploySessions.find("s1", 1, "abc"))
        assertEquals("l1: Downloading [==>  ] 1MB/2MB", env.container.deploySessions.progress(key).value?.pullStatus)
    }

    @Test
    fun unnamedNewContainersGetTheirOwnKeys() = runTest {
        val env = env()
        val vm = loaded(env, containerId = null)
        vm.setImage("nginx:1")
        vm.setStartAfterCreate(false)
        val review = reviewing(vm, FinishAction.Create)
        assertEquals("", review.name)
        vm.confirm()
        settle { vm.state.value.finish is FinishPhase.Deploying }
        val key = assertNotNull(env.container.deploySessions.find("s1", 1, NEW_SOURCE))
        assertTrue(key.id.startsWith("unnamed-"), key.id)
        settle { vm.state.value.navigation != null }
        assertEquals("", creates.single().first)
    }

    @Test
    fun replaceIsBlockedWhenTheFreshInspectHasAProtectedImage() = runTest {
        val vm = loaded(env())
        vm.setRestartPolicy("unless-stopped")
        image = "portainer/portainer-ce:2.21"
        val review = reviewing(vm, FinishAction.Replace)
        assertEquals(dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE, review.blocked)
        assertFalse(review.canConfirm)
    }

    @Test
    fun stackMemberWarningInReplaceButNotInCopy() = runTest {
        labels = """{"com.docker.compose.project":"shop"}"""
        val vm = loaded(env())
        val replace = reviewing(vm, FinishAction.Replace)
        val stackWarning = dev.silas.portainer.app.recreate.warningText(dev.silas.portainer.core.recreate.RecreateWarning.StackMember("shop"))
        assertTrue(stackWarning in replace.warnings, "${replace.warnings}")
        vm.backToForm()
        val copy = reviewing(vm, FinishAction.Copy)
        assertTrue(copy.warnings.none { it.contains("stack") }, "${copy.warnings}")
    }
}
