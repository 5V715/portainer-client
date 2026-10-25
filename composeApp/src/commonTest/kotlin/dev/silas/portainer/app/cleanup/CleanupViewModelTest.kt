package dev.silas.portainer.app.cleanup

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CleanupViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()
    private val failingDeletes = mutableSetOf<String>()
    private val failingInspects = mutableSetOf<String>()
    private val inspectImages = mutableMapOf<String, String>()

    private val a = "sha256:" + "a".repeat(64)
    private val b = "sha256:" + "b".repeat(64)
    private val d = "sha256:" + "d".repeat(64)
    private val p = "sha256:" + "c".repeat(64)
    private val anon = "e".repeat(64)

    // a: used (nginx, stopped container); b: unused tagged; d: unused dangling; p: Portainer.
    private val imagesJson = """[
        {"Id":"$a","RepoTags":["nginx:latest"],"Size":100,"Created":1},
        {"Id":"$b","RepoTags":["redis:7"],"Size":1000,"Created":1},
        {"Id":"$d","RepoTags":["<none>:<none>"],"Size":20,"Created":1},
        {"Id":"$p","RepoTags":["portainer/portainer-ce:latest"],"Size":1,"Created":1}
    ]"""
    private val volumesJson = """{"Volumes":[
        {"Name":"used","Driver":"local","Mountpoint":"/v/u","Scope":"local"},
        {"Name":"db","Driver":"local","Mountpoint":"/v/db","Scope":"local"},
        {"Name":"portainer_data","Driver":"local","Mountpoint":"/v/p","Scope":"local"},
        {"Name":"$anon","Driver":"local","Mountpoint":"/v/a","Scope":"local","Labels":{"com.docker.volume.anonymous":""}}
    ]}"""
    private val networksJson = """[
        {"Id":"n1","Name":"bridge","Driver":"bridge","Scope":"local"},
        {"Id":"n2","Name":"spare","Driver":"bridge","Scope":"local"},
        {"Id":"n3","Name":"spare2","Driver":"bridge","Scope":"local"}
    ]"""
    private var containersJson = """[
        {"Id":"c1","Names":["/web"],"Image":"nginx","ImageID":"$a","State":"exited","Status":"Exited (0)",
         "Mounts":[{"Type":"volume","Name":"used","Destination":"/d"}]},
        {"Id":"c2","Names":["/old"],"Image":"busybox","State":"exited","Status":"Exited (1)"},
        {"Id":"c3","Names":["/up"],"Image":"redis","State":"running","Status":"Up"}
    ]"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { req ->
            val path = req.url.encodedPath
            requests += "${req.method.value} $path"
            when {
                req.method == HttpMethod.Get && path.endsWith("/images/json") -> respond(imagesJson, HttpStatusCode.OK, json)
                req.method == HttpMethod.Get && path.endsWith("/volumes") -> respond(volumesJson, HttpStatusCode.OK, json)
                req.method == HttpMethod.Get && path.endsWith("/networks") -> respond(networksJson, HttpStatusCode.OK, json)
                req.method == HttpMethod.Get && path.endsWith("/containers/json") -> respond(containersJson, HttpStatusCode.OK, json)
                req.method == HttpMethod.Get && path.contains("/containers/") && path.endsWith("/json") -> {
                    val id = path.removeSuffix("/json").substringAfterLast('/')
                    val image = inspectImages[id]
                    if (id in failingInspects || image == null) {
                        respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json)
                    } else {
                        respond("""{"Id":"$id","Config":{"Image":"$image"}}""", HttpStatusCode.OK, json)
                    }
                }
                req.method == HttpMethod.Delete -> {
                    val target = path.substringAfterLast('/')
                    if (target in failingDeletes) {
                        respond("""{"message":"conflict: in use"}""", HttpStatusCode.Conflict, json)
                    } else {
                        respond("", HttpStatusCode.NoContent, json)
                    }
                }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    private fun deletes() = requests.filter { it.startsWith("DELETE") }

    private suspend fun TestScope.preview(kind: CleanupKind, onFinished: () -> Unit = {}): CleanupViewModel {
        val vm = CleanupViewModel(env().container, "s1", 1, kind, onFinished)
        vm.open()
        settle { vm.phase.value is CleanupPhase.Preview || vm.phase.value is CleanupPhase.Error }
        return vm
    }

    private suspend fun TestScope.done(vm: CleanupViewModel): CleanupPhase.Done {
        settle { vm.phase.value is CleanupPhase.Done }
        return vm.phase.value as CleanupPhase.Done
    }

    @Test
    fun namedVolumesNeedTheOptionAndTheSecondConfirmation() = runTest {
        val vm = preview(CleanupKind.Volumes)
        val p = assertIs<CleanupPhase.Preview>(vm.phase.value)
        assertEquals(listOf(anon), p.plan.toDelete.map { it.id })
        assertEquals(listOf("db"), p.plan.namedVolumes.map { it.id })
        assertEquals(listOf("portainer_data"), p.plan.kept.map { it.id })

        vm.setIncludeNamed(true)
        vm.confirm()
        assertIs<CleanupPhase.ConfirmNamed>(vm.phase.value)
        assertTrue(deletes().isEmpty())
        vm.cancelNamed()
        assertTrue(assertIs<CleanupPhase.Preview>(vm.phase.value).includeNamed)

        vm.confirm()
        vm.confirmNamed()
        done(vm)
        assertEquals(
            listOf("DELETE /api/endpoints/1/docker/volumes/$anon", "DELETE /api/endpoints/1/docker/volumes/db"),
            deletes(),
        )
    }

    @Test
    fun withoutTheOptionNamedVolumesAreNotDeletedAndThereIsNoSecondConfirmation() = runTest {
        val vm = preview(CleanupKind.Volumes)
        vm.confirm()
        done(vm)
        assertEquals(listOf("DELETE /api/endpoints/1/docker/volumes/$anon"), deletes())
    }

    @Test
    fun confirmNamedIsIgnoredOutsideTheSecondConfirmation() = runTest {
        val vm = preview(CleanupKind.Volumes)
        vm.setIncludeNamed(true)
        vm.confirmNamed()
        assertIs<CleanupPhase.Preview>(vm.phase.value)
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun togglingIncludeTaggedRebuildsThePreview() = runTest {
        val vm = preview(CleanupKind.Images)
        val before = assertIs<CleanupPhase.Preview>(vm.phase.value)
        assertEquals(listOf(d), before.plan.toDelete.map { it.id })
        assertEquals(listOf(p), before.plan.kept.map { it.id })
        val imageListsBefore = requests.count { it.endsWith("/images/json") }

        vm.setIncludeTagged(true)
        val after = assertIs<CleanupPhase.Preview>(vm.phase.value)
        assertTrue(after.includeTagged)
        assertEquals(listOf(b, d), after.plan.toDelete.map { it.id })
        assertEquals(imageListsBefore, requests.count { it.endsWith("/images/json") })

        vm.setIncludeTagged(false)
        assertEquals(listOf(d), assertIs<CleanupPhase.Preview>(vm.phase.value).plan.toDelete.map { it.id })
    }

    @Test
    fun aFailedItemAppearsInDoneFailures() = runTest {
        failingDeletes += b
        val vm = preview(CleanupKind.Images)
        vm.setIncludeTagged(true)
        vm.confirm()
        val result = done(vm)
        assertEquals(1, result.deleted)
        assertEquals(1, result.failed)
        assertEquals(20L, result.reclaimedBytes) // only the deleted item's size
        assertEquals(listOf(b), result.failures.map { it.item.id })
        assertEquals("conflict: in use", result.failures.single().message)
        assertEquals(2, deletes().size) // the failure doesn't stop the rest
    }

    @Test
    fun theDeleteListSentEqualsThePreviewListExactlyAndNeverForces() = runTest {
        val vm = preview(CleanupKind.Images)
        vm.setIncludeTagged(true)
        val previewed = assertIs<CleanupPhase.Preview>(vm.phase.value).plan.toDelete.map { it.id }
        vm.confirm()
        done(vm)
        assertEquals(previewed.map { "DELETE /api/endpoints/1/docker/images/$it" }, deletes())
        assertFalse(requests.any { it.contains("prune") })
    }

    @Test
    fun networksPreviewSkipsPredefinedAndDeletesThePreview() = runTest {
        val vm = preview(CleanupKind.Networks)
        val p = assertIs<CleanupPhase.Preview>(vm.phase.value)
        assertEquals(listOf("n2", "n3"), p.plan.toDelete.map { it.id })
        vm.confirm()
        done(vm)
        assertEquals(listOf("n2", "n3").map { "DELETE /api/endpoints/1/docker/networks/$it" }, deletes())
    }

    @Test
    fun idOnlyContainerImagesAreResolvedBeforeThePreview() = runTest {
        val idOnlyNginx = "f".repeat(64)
        containersJson = """[
            {"Id":"c1","Names":["/web"],"Image":"$idOnlyNginx","State":"exited","Status":"Exited (0)"},
            {"Id":"c2","Names":["/pt"],"Image":"$p","State":"exited","Status":"Exited (0)"},
            {"Id":"c3","Names":["/lost"],"Image":"$d","State":"exited","Status":"Exited (0)"},
            {"Id":"c4","Names":["/up"],"Image":"$b","State":"running","Status":"Up"},
            {"Id":"c5","Names":["/old"],"Image":"busybox","State":"exited","Status":"Exited (0)"},
            {"Id":"c6","Names":["/byid"],"Image":"$a","State":"exited","Status":"Exited (0)"}
        ]"""
        inspectImages["c1"] = "nginx:latest"
        inspectImages["c2"] = "portainer/portainer-ce:latest"
        failingInspects += "c3"
        inspectImages["c4"] = "redis:7"
        inspectImages["c6"] = a // created from an id: still unknown, so kept
        val vm = preview(CleanupKind.Containers)
        val plan = assertIs<CleanupPhase.Preview>(vm.phase.value).plan
        assertEquals(listOf("c1", "c5"), plan.toDelete.map { it.id })
        assertEquals(listOf("c2", "c3", "c6"), plan.kept.map { it.id })
        assertEquals("lost (image unknown)", plan.kept[1].label)
        // Every id-only container is inspected, running ones too; named images aren't.
        val inspected = requests.filter { it.startsWith("GET") && it.contains("/containers/c") }
        assertEquals(listOf("c1", "c2", "c3", "c4", "c6").map { "GET /api/endpoints/1/docker/containers/$it/json" }, inspected)
    }

    @Test
    fun theListRefreshesWhenTheCleanupFinishes() = runTest {
        var finished = 0
        val vm = preview(CleanupKind.Containers) { finished++ }
        assertEquals(0, finished)
        vm.confirm()
        done(vm)
        settle { finished == 1 }
        assertEquals(listOf("c1", "c2").map { "DELETE /api/endpoints/1/docker/containers/$it" }, deletes())
    }

    @Test
    fun emptyPlanCannotBeConfirmed() = runTest {
        containersJson = """[{"Id":"c3","Names":["/up"],"Image":"redis","State":"running","Status":"Up"}]"""
        val vm = preview(CleanupKind.Containers)
        val p = assertIs<CleanupPhase.Preview>(vm.phase.value)
        assertTrue(p.isEmpty)
        vm.confirm()
        assertIs<CleanupPhase.Preview>(vm.phase.value)
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun loadFailureShowsAnErrorWithoutTheApiKey() = runTest {
        val env = TestEnv { respond("""{"message":"server exploded"}""", HttpStatusCode.InternalServerError, json) }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "secret-key")
        val vm = CleanupViewModel(env.container, "s1", 1, CleanupKind.Volumes)
        vm.open()
        settle { vm.phase.value is CleanupPhase.Error }
        val message = (vm.phase.value as CleanupPhase.Error).message
        assertFalse(message.contains("secret-key"), message)
    }
}
