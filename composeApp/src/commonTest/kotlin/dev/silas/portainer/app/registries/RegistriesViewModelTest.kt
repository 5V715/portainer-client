package dev.silas.portainer.app.registries

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.model.RegistryType
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RegistriesViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val listJson = """[
        {"Id":1,"Name":"hub","Type":6,"URL":"docker.io","Authentication":true,"Username":"bob"},
        {"Id":2,"Name":"mine","Type":3,"URL":"reg.example.com","Authentication":false},
        {"Id":3,"Name":"odd","Type":99,"URL":"x.example.com","Authentication":false}]"""

    private class Recorded(val method: HttpMethod, val path: String, val body: JsonObject?)

    private val calls = mutableListOf<Recorded>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { req ->
            val bytes = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()
            calls += Recorded(req.method, req.url.encodedPath, bytes?.let { Json.parseToJsonElement(it.decodeToString()).jsonObject })
            when {
                req.method == HttpMethod.Get -> respond(listJson, HttpStatusCode.OK, json)
                req.method == HttpMethod.Post ->
                    respond("""{"Id":9,"Name":"n","Type":8,"URL":"ghcr.io"}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    @Test
    fun listLoadsRegistries() = runTest {
        val vm = RegistriesViewModel(env().container, "s1")
        settle { !vm.state.value.loading }
        val items = vm.state.value.items
        assertEquals(listOf("hub", "mine", "odd"), items.map { it.name })
        assertEquals(RegistryType.Unknown, items[2].type)
        assertEquals("Authenticated as bob", authLabel(items[0]))
        assertEquals("Anonymous", authLabel(items[1]))
        assertEquals("Docker Hub", typeLabel(RegistryType.DockerHub))
        assertEquals("GitHub (ghcr.io)", typeLabel(RegistryType.GitHub))
    }

    @Test
    fun savingNewGithubRegistryPostsFixedUrl() = runTest {
        val vm = RegistryEditViewModel(env().container, "s1", null)
        settle { !vm.loading }
        vm.type = RegistryType.GitHub
        vm.name = "gh"
        vm.authentication = true
        vm.username = "octo"
        vm.password = "tok"
        vm.save()
        settle { vm.saved }
        val post = calls.single { it.method == HttpMethod.Post }
        val body = assertNotNull(post.body)
        assertEquals("ghcr.io", body["URL"]!!.jsonPrimitive.content)
        assertEquals(8, body["Type"]!!.jsonPrimitive.int)
        assertTrue(body["Authentication"]!!.jsonPrimitive.boolean)
        assertEquals("tok", body["Password"]!!.jsonPrimitive.content)
        assertEquals("", vm.password)
    }

    @Test
    fun editWithEmptyPasswordSendsNoPassword() = runTest {
        val vm = RegistryEditViewModel(env().container, "s1", 1)
        settle { !vm.loading }
        assertEquals("hub", vm.name)
        assertEquals("bob", vm.username)
        vm.name = "hub2"
        vm.save()
        settle { vm.saved }
        val put = calls.single { it.method == HttpMethod.Put }
        assertEquals("/api/registries/1", put.path)
        val body = assertNotNull(put.body)
        assertFalse("Password" in body)
        assertEquals("bob", body["Username"]!!.jsonPrimitive.content)
        assertEquals("docker.io", body["URL"]!!.jsonPrimitive.content)
    }

    @Test
    fun validationShowsInlineErrorsAndSendsNothing() = runTest {
        val vm = RegistryEditViewModel(env().container, "s1", null)
        settle { !vm.loading }
        vm.type = RegistryType.Custom
        vm.authentication = true
        vm.save()
        advanceUntilIdle()
        assertNotNull(vm.errors.name)
        assertNotNull(vm.errors.url)
        assertNotNull(vm.errors.username)
        assertNotNull(vm.errors.password)
        assertTrue(calls.none { it.method == HttpMethod.Post })
    }

    @Test
    fun authenticationOffSendsNoCredentials() = runTest {
        val vm = RegistryEditViewModel(env().container, "s1", null)
        settle { !vm.loading }
        vm.type = RegistryType.Custom
        vm.name = "c"
        vm.url = "reg.example.com"
        vm.username = "left"
        vm.password = "over"
        vm.authentication = false
        vm.save()
        settle { vm.saved }
        val body = assertNotNull(calls.single { it.method == HttpMethod.Post }.body)
        assertFalse("Username" in body)
        assertFalse("Password" in body)
        assertEquals("", vm.password)
    }

    @Test
    fun deleteCallsApiAndRefreshes() = runTest {
        val vm = RegistriesViewModel(env().container, "s1")
        settle { !vm.state.value.loading }
        vm.delete(2)
        settle { calls.any { it.method == HttpMethod.Delete } && calls.count { it.method == HttpMethod.Get } >= 2 }
        assertEquals("/api/registries/2", calls.single { it.method == HttpMethod.Delete }.path)
        assertNull(vm.state.value.error)
    }
}
