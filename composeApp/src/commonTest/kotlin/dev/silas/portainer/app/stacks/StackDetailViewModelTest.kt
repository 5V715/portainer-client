package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
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
import kotlinx.coroutines.test.TestScope
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
class StackDetailViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value

    private var stackJson = """{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":1,
        "CreationDate":1700000000,"UpdateDate":1700086400,"UpdatedBy":"alice"}"""
    private var fileContent = "services:\n  web:\n    image: nginx\n  db:\n    image: postgres\n"
    private var fileStatus = HttpStatusCode.OK
    private var containersJson = """[
        {"Id":"c1","Names":["/shop-web-1"],"Image":"nginx","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"shop"}},
        {"Id":"c2","Names":["/shop-db-1"],"Image":"postgres","State":"exited","Status":"Exited (0)","Labels":{"com.docker.compose.project":"shop"}},
        {"Id":"c3","Names":["/other"],"Image":"redis","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"other"}}
    ]"""
    private var failAll = false

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(key: String = "test-api-key"): TestEnv {
        val env = TestEnv { req ->
            val path = req.url.encodedPath
            requestLog.update { it + "${req.method.value} $path" }
            when {
                failAll -> respond("""{"message":"boom $key"}""", HttpStatusCode.InternalServerError, json)
                path == "/api/status" -> respond("""{"Version":"2.45.3"}""", HttpStatusCode.OK, json)
                path == "/api/stacks" -> respond("[]", HttpStatusCode.OK, json)
                path == "/api/stacks/5" -> respond(stackJson, HttpStatusCode.OK, json)
                path == "/api/stacks/5/file" ->
                    if (fileStatus == HttpStatusCode.OK) {
                        val escaped = fileContent.replace("\n", "\\n")
                        respond("""{"StackFileContent":"$escaped"}""", HttpStatusCode.OK, json)
                    } else {
                        respond("""{"message":"Stack git settings have changed"}""", fileStatus, json)
                    }
                path.endsWith("/containers/json") -> respond(containersJson, HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), key)
        return env
    }

    private suspend fun TestScope.loaded(stackId: Int? = 5, name: String = "shop"): StackDetailViewModel {
        val vm = StackDetailViewModel(env().container, "s1", 1, stackId, name)
        settle { !vm.state.value.loading && (vm.state.value.stack != null || vm.state.value.error != null) }
        return vm
    }

    @Test
    fun showsRunningAndStoppedMembersFileDatesAndFeatures() = runTest {
        val vm = loaded()
        val s = vm.state.value
        val stack = assertNotNull(s.stack)
        assertEquals("shop", stack.name)
        assertEquals(StackStatus.Active, stack.status)
        // Sorted by name: shop-db-1 (stopped) before shop-web-1 (running).
        assertEquals(listOf("c2", "c1"), s.containers.map { it.id })
        assertEquals(listOf(ContainerState.Exited, ContainerState.Running), s.containers.map { it.state })
        assertEquals(fileContent, s.file)
        assertFalse(s.protected)
        assertTrue(s.features.asyncDeploy) // gated on 2.45.3
        assertEquals(1_700_000_000L, stack.createdAt)
        assertEquals(1_700_086_400L, stack.updatedAt)
        assertEquals("alice", stack.updatedBy)
        assertEquals("2023-11-14 22:13 UTC", formatStackDate(stack.createdAt))
        assertEquals("2023-11-15 22:13 UTC", formatStackDate(stack.updatedAt))
        assertNull(s.error)
    }

    @Test
    fun stackWithoutContainersIsInactiveAndProtectedFromItsFile() = runTest {
        stackJson = """{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":2}"""
        containersJson = "[]"
        fileContent = "services:\n  agent:\n    image: portainer/portainer-ce:2.45.3\n"
        val vm = loaded()
        val s = vm.state.value
        assertEquals(StackStatus.Inactive, s.stack?.status)
        assertTrue(s.containers.isEmpty())
        assertTrue(s.protected)
    }

    @Test
    fun memberRunningPortainerAgentIsProtected() = runTest {
        containersJson = """[
            {"Id":"c1","Names":["/shop-agent-1"],"Image":"portainer/agent:2.45.3","State":"exited","Status":"Exited (0)","Labels":{"com.docker.compose.project":"shop"}}
        ]"""
        fileContent = "services:\n  web:\n    image: nginx\n"
        val vm = loaded()
        assertTrue(vm.state.value.protected)
    }

    @Test
    fun errorStatusCarriesTheStatusMessage() = runTest {
        stackJson = """{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":4,"DeploymentStatus":[{"Status":4,"Message":"pull failed"}]}"""
        val vm = loaded()
        assertEquals(StackStatus.Error, vm.state.value.stack?.status)
        assertEquals("pull failed", vm.state.value.stack?.statusMessage)
    }

    @Test
    fun gitStackShowsItsConfigAndAFileThatFailsToLoadIsNotFatal() = runTest {
        stackJson = """{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":1,
            "GitConfig":{"URL":"https://git.example.com/shop.git","ReferenceName":"refs/heads/main","ConfigFilePath":"deploy/compose.yml"}}"""
        fileStatus = HttpStatusCode.Conflict
        val vm = loaded()
        val s = vm.state.value
        val git = assertNotNull(s.stack?.git)
        assertEquals("https://git.example.com/shop.git", git.url)
        assertEquals("refs/heads/main", git.referenceName)
        assertEquals("deploy/compose.yml", git.configFilePath)
        assertNull(s.file)
        assertEquals("Stack git settings have changed", s.fileError)
        assertNull(s.error)
        assertEquals(2, s.containers.size)
    }

    @Test
    fun externalStackLoadsFromContainersWithoutAFile() = runTest {
        val vm = loaded(stackId = null, name = "other")
        val s = vm.state.value
        assertEquals(StackControl.Limited, s.stack?.control)
        assertEquals(listOf("c3"), s.containers.map { it.id })
        assertNull(s.file)
        assertFalse(requests.any { it.contains("/file") }, requests.toString())
    }

    @Test
    fun loadFailureShowsAnErrorWithoutTheApiKeyAndNoCrash() = runTest {
        failAll = true
        val vm = StackDetailViewModel(env(key = "secret-key").container, "s1", 1, 5, "shop")
        settle { vm.state.value.error != null && !vm.state.value.loading }
        val message = assertNotNull(vm.state.value.error)
        assertFalse(message.contains("secret-key"), message)
        assertNull(vm.state.value.stack)
    }
}
