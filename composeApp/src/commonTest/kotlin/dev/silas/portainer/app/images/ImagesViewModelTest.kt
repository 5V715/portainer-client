package dev.silas.portainer.app.images

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
    /** When set, the pull answers with this stream, which the test writes line by line. */
    private var pullStream: ByteChannel? = null
    /** `Config.Image` answered by a container inspect, by container id. */
    private val inspectImages = mutableMapOf<String, String>()
    private val old = "sha256:" + "e".repeat(64)

    private val a = "sha256:" + "a".repeat(64)
    private val b = "sha256:" + "b".repeat(64)
    private val p = "sha256:" + "c".repeat(64)

    private var imagesJson = """[
        {"Id":"$a","RepoTags":["nginx:latest"],"Size":142000000,"Created":1700000000},
        {"Id":"$b","RepoTags":["redis:7"],"Size":1000,"Created":1700000000},
        {"Id":"$p","RepoTags":["portainer/portainer-ce:latest"],"Size":1,"Created":1700000000}
    ]"""
    // A stopped container still uses nginx, so only redis and portainer-less images count as unused.
    private var containersJson = """[
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
                req.method == HttpMethod.Post && path.endsWith("/images/create") ->
                    pullStream?.let { respond(it, HttpStatusCode.OK, json) } ?: respond(pullBody, HttpStatusCode.OK, json)
                req.method == HttpMethod.Get && path.contains("/containers/") && path.endsWith("/json") -> {
                    val id = path.removeSuffix("/json").substringAfterLast('/')
                    inspectImages[id]?.let { respond("""{"Id":"$id","Config":{"Image":"$it"}}""", HttpStatusCode.OK, json) }
                        ?: respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json)
                }
                req.method == HttpMethod.Delete -> respond(deleteBody, deleteStatus, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    private fun deletes() = requests.filter { it.startsWith("DELETE") }

    private fun listLoads() = requests.count { it.endsWith("/images/json") }

    /** The old Portainer image lost its tag but the stopped `portainer_old` container still uses it. */
    private fun untaggedPortainerImage(containerImage: String) {
        imagesJson = """[
            {"Id":"$b","RepoTags":["redis:7"],"Size":1000,"Created":1700000000},
            {"Id":"$old","RepoTags":["<none>:<none>"],"Size":1,"Created":1600000000}
        ]"""
        containersJson = """[
            {"Id":"po","Names":["/portainer_old"],"Image":"$containerImage","ImageID":"$old","State":"exited","Status":"Exited (0)"}
        ]"""
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertRemovalRefused(vm: ImagesViewModel) {
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        assertTrue(vm.state.value.items.first { it.image.id == old }.protected)
        assertFalse(vm.state.value.items.first { it.image.id == b }.protected)
        // A 409 would otherwise lead to the force prompt.
        deleteStatus = HttpStatusCode.Conflict
        deleteBody = """{"message":"conflict: unable to delete eee (must be forced) - image is being used by stopped container po"}"""
        vm.remove(old, force = false)
        vm.remove(old, force = true)
        settle { vm.state.value.error != null }
        advanceUntilIdle()
        assertEquals(PROTECTED_IMAGE_REMOVE_MESSAGE, vm.state.value.error)
        assertNull(vm.state.value.forcePrompt)
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun untaggedImageUsedByAStoppedPortainerContainerIsProtected() = runTest {
        untaggedPortainerImage("portainer/portainer-ce")
        assertRemovalRefused(ImagesViewModel(env().container, "s1", 1))
    }

    @Test
    fun untaggedImageOfAPortainerContainerListedByIdIsProtectedAfterInspect() = runTest {
        untaggedPortainerImage(old)
        inspectImages["po"] = "portainer/portainer-ce:2.19"
        assertRemovalRefused(ImagesViewModel(env().container, "s1", 1))
        assertTrue(requests.contains("GET /api/endpoints/1/docker/containers/po/json"))
    }

    @Test
    fun imageOfAContainerWhoseImageCannotBeRecognisedIsProtected() = runTest {
        untaggedPortainerImage(old) // inspect fails: the container's image stays unknown
        assertRemovalRefused(ImagesViewModel(env().container, "s1", 1))
    }

    @Test
    fun failedRemoveRefreshesTheList() = runTest {
        val vm = ImagesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        deleteStatus = HttpStatusCode.NotFound
        deleteBody = """{"message":"No such image"}"""
        vm.remove(b, force = false)
        settle { vm.state.value.error != null && listLoads() == 2 && !vm.state.value.loading }
        assertNull(vm.state.value.forcePrompt)

        deleteStatus = HttpStatusCode.InternalServerError
        deleteBody = """{"message":"boom"}"""
        vm.remove(b, force = false)
        settle { listLoads() == 3 && !vm.state.value.loading }
        assertEquals("boom", vm.state.value.error)
    }

    @Test
    fun failedForcedRemoveRefreshesTheList() = runTest {
        val vm = ImagesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        deleteStatus = HttpStatusCode.Conflict
        deleteBody = """{"message":"conflict: unable to delete bbb (must be forced) - image is being used by stopped container c9"}"""
        vm.remove(b, force = false)
        settle { vm.state.value.forcePrompt != null }
        assertEquals(1, listLoads())
        vm.confirmForce() // forced: Docker's 409 is now an error, not another prompt
        settle { vm.state.value.error != null && listLoads() == 2 && !vm.state.value.loading }
        assertNull(vm.state.value.forcePrompt)
        assertEquals(listOf("DELETE /api/endpoints/1/docker/images/$b", "DELETE /api/endpoints/1/docker/images/$b"), deletes())
    }

    @Test
    fun forceIsNotOfferedWhenDockerSaysItCannotBeForced() = runTest {
        val vm = ImagesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        deleteStatus = HttpStatusCode.Conflict
        val docker = "conflict: unable to delete bbb (cannot be forced) - image is being used by running container c3"
        deleteBody = """{"message":"$docker"}"""
        vm.remove(b, force = false)
        settle { vm.state.value.error != null && listLoads() == 2 && !vm.state.value.loading }
        assertEquals(docker, vm.state.value.error)
        assertNull(vm.state.value.forcePrompt)
        assertEquals(1, deletes().size)
        assertTrue(cannotBeForced("image is being used by running container 1"))
        assertFalse(cannotBeForced("conflict: unable to delete b (must be forced) - image is referenced in multiple repositories"))
    }

    @Test
    fun pullShowsTheLatestStreamLineWhilePulling() = runTest {
        val stream = ByteChannel(autoFlush = true)
        pullStream = stream
        val vm = ImagesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        vm.pull("nginx:latest", null)
        stream.writeStringUtf8("""{"status":"Pulling from library/nginx","id":"latest"}""" + "\n")
        settle { vm.state.value.pull.progress == "latest: Pulling from library/nginx" }
        stream.writeStringUtf8("""{"status":"Downloading","progress":"[=>  ] 1MB/5MB","id":"a1b2"}""" + "\n")
        settle { vm.state.value.pull.progress == "a1b2: Downloading [=>  ] 1MB/5MB" }
        assertEquals(PullStatus.Pulling, vm.state.value.pull.status)
        stream.flushAndClose()
        settle { vm.state.value.pull.status is PullStatus.Done }
        assertNull(vm.state.value.pull.progress)
        // A finished pull reloads the list on the engine's threads: let it finish so nothing touches Main after the test.
        settle { imageListCalls == 2 && !vm.state.value.loading }
        vm.viewModelScope.cancel()
        advanceUntilIdle()
    }

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
