package dev.silas.portainer.app.servers

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun okEnv() = TestEnv { request ->
        if (request.url.encodedPath.endsWith("/api/status")) respond("""{"Version":"2.21.0"}""", HttpStatusCode.OK, json)
        else respond("""[{"Id":1,"Name":"local","Type":1,"Status":1}]""", HttpStatusCode.OK, json)
    }

    @Test
    fun invalidKeySetsErrorAndSavesNothing() = runTest {
        val env = TestEnv { respond("""{"message":"nope"}""", HttpStatusCode.Unauthorized, json) }
        val vm = ServerEditViewModel(env.container, null)
        vm.name = "Home"
        vm.url = "portainer.example.com"
        vm.apiKey = "bad"
        vm.save()
        settle { vm.error != null }
        assertEquals(SaveError.InvalidKey, vm.error)
        assertEquals("Invalid API key", vm.error?.text)
        assertFalse(vm.saved)
        assertTrue(env.repo.servers.value.isEmpty())
        assertTrue(env.secrets.map.isEmpty())
    }

    @Test
    fun validServerPersistsNormalisedUrlAndKey() = runTest {
        val env = okEnv()
        val vm = ServerEditViewModel(env.container, null)
        vm.name = "Home"
        vm.url = "  portainer.example.com/ "
        vm.apiKey = "good-key"
        vm.save()
        settle { vm.saved }
        assertNull(vm.error)
        assertTrue(vm.saved)
        val saved = env.repo.servers.value.single()
        assertEquals("https://portainer.example.com", saved.baseUrl)
        assertEquals("Home", saved.name)
        assertEquals("good-key", env.repo.apiKey(saved.id))
    }

    @Test
    fun httpUrlIsRejectedWithoutNetwork() = runTest {
        val env = okEnv()
        val vm = ServerEditViewModel(env.container, null)
        vm.url = "http://portainer.local"
        vm.apiKey = "k"
        vm.save()
        advanceUntilIdle()
        assertEquals("Portainer must be reached over HTTPS", vm.error?.text)
        assertTrue(env.repo.servers.value.isEmpty())
    }

    @Test
    fun editWithEmptyKeyKeepsStoredKey() = runTest {
        val env = okEnv()
        env.repo.save(ServerConfig("s1", "Old", "https://old.example.com", 3), "stored-key")
        val vm = ServerEditViewModel(env.container, "s1")
        assertEquals("", vm.apiKey)
        vm.name = "New"
        vm.save()
        settle { vm.saved }
        assertTrue(vm.saved)
        val saved = env.repo.servers.value.single()
        assertEquals("New", saved.name)
        assertEquals(3, saved.lastEndpointId)
        assertEquals("stored-key", env.repo.apiKey("s1"))
    }

    @Test
    fun deleteRemovesServer() = runTest {
        val env = okEnv()
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        val vm = ServersViewModel(env.container)
        vm.delete("s1")
        advanceUntilIdle()
        assertTrue(env.repo.servers.value.isEmpty())
    }
}
