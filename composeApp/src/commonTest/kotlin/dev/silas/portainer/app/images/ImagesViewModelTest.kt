package dev.silas.portainer.app.images

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImagesViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    // Appended from the engine's threads while the test reads it: an atomic snapshot list.
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private var imageListCalls = 0
    private var deleteStatus = HttpStatusCode.OK
    private var deleteBody = "[]"
    private var pullBody = """{"status":"Pulling from library/nginx"}""" + "\n"

    private val a = "sha256:" + "a".repeat(64)
    private val b = "sha256:" + "b".repeat(64)
    private val p = "sha256:" + "c".repeat(64)

    private val imagesJson = """[
        {"Id":"$a","RepoTags":["nginx:latest"],"Size":142000000,"Created":1700000000},
        {"Id":"$b","RepoTags":["redis:7"],"Size":1000,"Created":1700000000},
        {"Id":"$p","RepoTags":["portainer/portainer-ce:latest"],"Size":1,"Created":1700000000}
    ]"""
    // A stopped container still uses nginx, so only redis and portainer-less images count as unused.
    private val containersJson = """[
        {"Id":"c1","Names":["/web"],"Image":"nginx","ImageID":"$a","State":"exited","Status":"Exited (0)"}
    ]"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { req ->
            val path = req.url.encodedPath
            requestLog.update { it + "${req.method.value} $path" }
            when {
                req.method == HttpMethod.Get && path.endsWith("/images/json") -> {
                    imageListCalls++
                    respond(imagesJson, HttpStatusCode.OK, json)
                }
                req.method == HttpMethod.Get && path.endsWith("/containers/json") -> respond(containersJson, HttpStatusCode.OK, json)
                req.method == HttpMethod.Get && path.endsWith("/api/registries") -> respond("[]", HttpStatusCode.OK, json)
                req.method == HttpMethod.Post && path.endsWith("/images/create") -> respond(pullBody, HttpStatusCode.OK, json)
                req.method == HttpMethod.Delete -> respond(deleteBody, deleteStatus, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    private fun deletes() = requests.filter { it.startsWith("DELETE") }

    @Test
    fun unusedBadgeCountsStoppedContainers() = runTest {
        val vm = ImagesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        val used = vm.state.value.items.associate { it.image.id to it.used }
        assertEquals(true, used[a])
        assertEquals(false, used[b])
    }

    @Test
    fun protectedImageRemovalIsRefusedWithoutApiCall() = runTest {
        val vm = ImagesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        vm.remove(p, force = false)
        vm.remove(p, force = true)
        settle { vm.state.value.error != null }
        assertEquals("This image runs Portainer and can't be removed from this app.", vm.state.value.error)
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun conflictShowsDockerMessageAndOffersForce() = runTest {
        val vm = ImagesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        deleteStatus = HttpStatusCode.Conflict
        deleteBody = """{"message":"conflict: unable to delete aaa (must be forced) - image is being used by stopped container c1"}"""
        vm.remove(b, force = false)
        settle { vm.state.value.forcePrompt != null }
        val prompt = assertNotNull(vm.state.value.forcePrompt)
        assertEquals(b, prompt.id)
        assertTrue(prompt.message.contains("image is being used by stopped container"))
        assertNull(vm.state.value.error)

        deleteStatus = HttpStatusCode.OK
        vm.confirmForce()
        settle { vm.state.value.forcePrompt == null && deletes().size == 2 && !vm.state.value.loading }
        assertEquals(2, imageListCalls)
    }

    @Test
    fun pullSuccessRefreshesList() = runTest {
        val vm = ImagesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        vm.pull("nginx:latest", null)
        settle { vm.state.value.pull.status is PullStatus.Done }
        settle { imageListCalls == 2 && !vm.state.value.loading }
        assertTrue(requests.contains("POST /api/endpoints/1/docker/images/create"))
    }

    @Test
    fun pullErrorInStreamIsFailure() = runTest {
        pullBody = """{"error":"manifest unknown"}""" + "\n"
        val vm = ImagesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        vm.pull("nginx:nope", null)
        settle { vm.state.value.pull.status is PullStatus.Failed }
        assertFalse(imageListCalls > 1)
    }

    @Test
    fun formatsBytesAndAge() {
        assertEquals("142 MB", formatBytes(142_000_000))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.5 GB", formatBytes(1_500_000_000))
        val now = 1_700_000_000L + 3 * 86_400
        assertEquals("3 days ago", formatCreated(1_700_000_000L, now))
        assertEquals("today", formatCreated(1_700_000_000L, 1_700_000_000L + 100))
        assertEquals("2023-11-14", formatCreated(1_700_000_000L, now + 40 * 86_400))
    }
}
