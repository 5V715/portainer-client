package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.model.StackControl
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StacksViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())

    private val stacksJson = """[
        {"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":1},
        {"Id":6,"Name":"blog","Type":2,"EndpointId":1,"Status":2,
         "GitConfig":{"URL":"https://git.example.com/blog.git","ReferenceName":"refs/heads/main","ConfigFilePath":"compose.yml"}},
        {"Id":7,"Name":"broken","Type":2,"EndpointId":1,"Status":4,"DeploymentStatus":[{"Status":4,"Message":"pull failed"}]},
        {"Id":8,"Name":"rolling","Type":2,"EndpointId":1,"Status":3}
    ]"""
    private val containersJson = """[
        {"Id":"c1","Names":["/shop-web-1"],"Image":"nginx","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"shop"}},
        {"Id":"c2","Names":["/legacy-db-1"],"Image":"postgres","State":"exited","Status":"Exited (0)","Labels":{"com.docker.compose.project":"legacy"}}
    ]"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(key: String = "k", fail: Boolean = false): TestEnv {
        val env = TestEnv { req ->
            val path = req.url.encodedPath
            requestLog.update { it + "${req.method.value} $path" }
            when {
                fail -> respond("""{"message":"boom $key"}""", HttpStatusCode.InternalServerError, json)
                path == "/api/stacks" -> respond(stacksJson, HttpStatusCode.OK, json)
                path.endsWith("/containers/json") -> respond(containersJson, HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), key)
        return env
    }

    private suspend fun kotlinx.coroutines.test.TestScope.loaded(): StacksViewModel {
        val vm = StacksViewModel(env().container, "s1", 1)
        settle { vm.state.value.stacks.isNotEmpty() && !vm.state.value.loading }
        return vm
    }

    @Test
    fun listShowsNameTypeStatusAndControl() = runTest {
        val vm = loaded()
        val byName = vm.state.value.visible.associateBy { it.name }
        assertEquals(listOf("blog", "broken", "legacy", "rolling", "shop"), vm.state.value.visible.map { it.name })

        val shop = byName.getValue("shop")
        assertEquals("Web editor", shop.deploymentLabel)
        assertEquals("active", stackStatus(shop).label)
        assertEquals("Full control", shop.controlLabel)

        val blog = byName.getValue("blog")
        assertEquals("Git repository", blog.deploymentLabel)
        assertEquals("inactive", stackStatus(blog).label)

        assertEquals("error", stackStatus(byName.getValue("broken")).label)
        assertEquals("deploying", stackStatus(byName.getValue("rolling")).label)
    }

    @Test
    fun externalStackIsShownAsLimited() = runTest {
        val vm = loaded()
        val legacy = vm.state.value.visible.single { it.name == "legacy" }
        assertEquals(StackControl.Limited, legacy.control)
        assertEquals(null, legacy.id)
        assertEquals("Limited — created outside Portainer", legacy.controlLabel)
        assertEquals("External", legacy.deploymentLabel)
        assertEquals("inactive", stackStatus(legacy).label)
    }

    @Test
    fun searchFiltersByName() = runTest {
        val vm = loaded()
        vm.setQuery("SH")
        assertEquals(listOf("shop"), vm.state.value.visible.map { it.name })
        vm.setQuery("zzz")
        assertTrue(vm.state.value.visible.isEmpty())
    }

    @Test
    fun loadFailureShowsAnErrorWithoutTheApiKey() = runTest {
        val vm = StacksViewModel(env(key = "secret-key", fail = true).container, "s1", 1)
        settle { vm.state.value.error != null && !vm.state.value.loading }
        val message = assertNotNull(vm.state.value.error)
        assertFalse(message.contains("secret-key"), message)
        assertTrue(vm.state.value.stacks.isEmpty())
    }
}
