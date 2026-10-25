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
import dev.silas.portainer.core.cleanup.CleanupItem
import dev.silas.portainer.app.recreate.SessionKey
import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.recreate.ChangeSummary
import dev.silas.portainer.core.recreate.RecreatePlan
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
    // Appended from the engine's threads while the test reads it: an atomic snapshot list.
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private val failingDeletes = mutableSetOf<String>()
    private val failingInspects = mutableSetOf<String>()
    private val inspectImages = mutableMapOf<String, String>()
    /** When set, every DELETE waits for it, so a run can be observed while Running. */
    private var deleteGate: CompletableDeferred<Unit>? = null
    /** Holds a recreate's first step (the pull) until completed, so the recreate stays in progress. */
    private val recreateGate = CompletableDeferred<Unit>()
    private var testEnv: TestEnv? = null

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
    fun tearDown() {
        recreateGate.complete(Unit)
        Dispatchers.resetMain()
    }

    /** Starts a recreate of `c2` that stays in progress until [recreateGate] completes. */
    private suspend fun startRecreate(): SessionKey {
        val container = env().container
        val key = SessionKey("s1", 1, "c2")
        val plan = RecreatePlan(
            containerName = "old",
            wasRunning = false,
            createBody = JsonObject(emptyMap()),
            extraNetworks = emptyMap(),
            image = ImageRef.parse("busybox"),
            pull = true,
            registryId = null,
            changes = ChangeSummary(null, emptyList(), emptyList(), emptyList(), emptyList()),
            warnings = emptyList(),
        )
        container.recreateSessions.start(key, container.dedicatedClient("s1"), "c2", plan)
        assertTrue(container.recreateSessions.isRunning("s1", 1))
        return key
    }

    private suspend fun env(): TestEnv = testEnv ?: newEnv().also { testEnv = it }

    private suspend fun newEnv(): TestEnv {
        val env = TestEnv { req ->
            val path = req.url.encodedPath
            requestLog.update { it + "${req.method.value} $path" }
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
                req.method == HttpMethod.Post && path.endsWith("/images/create") -> {
                    recreateGate.await()
                    respond("{}", HttpStatusCode.OK, json)
                }
                req.method == HttpMethod.Delete -> {
                    deleteGate?.await()
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
        assertEquals(listOf(CleanupItem(d, "<none> ${"d".repeat(12)}", 20L)), result.deletedItems)
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

    @Test
    fun aNewViewModelReattachesToARunningAndThenFinishedCleanup() = runTest {
        deleteGate = CompletableDeferred()
        val first = preview(CleanupKind.Networks)
        first.confirm()
        settle { first.phase.value is CleanupPhase.Running }
        // The screen was left (tab switch) and opened again: a new ViewModel for the same key.
        var refreshed = 0
        val second = CleanupViewModel(env().container, "s1", 1, CleanupKind.Networks) { refreshed++ }
        assertIs<CleanupPhase.Running>(second.phase.value)
        second.open() // ignored while running
        assertIs<CleanupPhase.Running>(second.phase.value)
        deleteGate!!.complete(Unit)
        val result = done(second)
        assertEquals(listOf("n2", "n3"), result.deletedItems.map { it.id })
        settle { refreshed == 1 }

        // Finished while away: yet another ViewModel shows the result and refreshes its list.
        var refreshedLater = 0
        val third = CleanupViewModel(env().container, "s1", 1, CleanupKind.Networks) { refreshedLater++ }
        assertIs<CleanupPhase.Done>(third.phase.value)
        settle { refreshedLater == 1 }
        third.acknowledge()
        settle { third.phase.value == CleanupPhase.Idle }
        assertEquals(2, deletes().size)
    }

    @Test
    fun aSecondRunForTheSameKeyIsRefusedWhileOneIsActive() = runTest {
        deleteGate = CompletableDeferred()
        val first = preview(CleanupKind.Networks)
        val items = assertIs<CleanupPhase.Preview>(first.phase.value).selected
        first.confirm()
        val key = CleanupKey("s1", 1, CleanupKind.Networks)
        assertFalse(env().container.cleanupSessions.start(key, items))
        val second = CleanupViewModel(env().container, "s1", 1, CleanupKind.Networks)
        second.confirm()
        deleteGate!!.complete(Unit)
        done(first)
        assertEquals(listOf("n2", "n3").map { "DELETE /api/endpoints/1/docker/networks/$it" }, deletes())
    }

    @Test
    fun doubleConfirmSendsEachDeleteOnce() = runTest {
        val vm = preview(CleanupKind.Networks)
        vm.confirm()
        vm.confirm()
        done(vm)
        assertEquals(listOf("n2", "n3").map { "DELETE /api/endpoints/1/docker/networks/$it" }, deletes())
    }

    @Test
    fun optionTogglesAreIgnoredDuringTheSecondConfirmation() = runTest {
        val vm = preview(CleanupKind.Volumes)
        vm.setIncludeNamed(true)
        vm.confirm()
        val confirming = assertIs<CleanupPhase.ConfirmNamed>(vm.phase.value)
        vm.setIncludeNamed(false)
        vm.setIncludeTagged(true)
        assertEquals(confirming, vm.phase.value)
        vm.confirmNamed()
        done(vm)
        assertEquals(2, deletes().size)
    }

    @Test
    fun aRunThatCannotStartEndsInDoneWithAnErrorNote() = runTest {
        val vm = preview(CleanupKind.Networks)
        env().repo.delete("s1") // the server is gone: no client for the run
        vm.confirm()
        val result = done(vm)
        assertEquals(0, result.deleted)
        assertTrue(result.deletedItems.isEmpty())
        assertEquals("Unknown server", result.error)
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun containerCleanupIsRefusedWhileARecreateRuns() = runTest {
        startRecreate()
        val vm = CleanupViewModel(env().container, "s1", 1, CleanupKind.Containers)
        vm.open()
        assertEquals(CleanupPhase.Error(RECREATE_IN_PROGRESS_MESSAGE), vm.phase.value)
        assertTrue(vm.sheetVisible.value)
        assertTrue(requests.none { it.endsWith("/containers/json") })

        // Other kinds are not affected; another endpoint is not affected either.
        assertIs<CleanupPhase.Preview>(preview(CleanupKind.Networks).phase.value)
        val other = CleanupViewModel(env().container, "s1", 2, CleanupKind.Containers)
        other.open()
        settle { other.phase.value is CleanupPhase.Preview }
    }

    @Test
    fun aRecreateStartedAfterThePreviewBlocksTheRun() = runTest {
        val vm = preview(CleanupKind.Containers)
        assertTrue(assertIs<CleanupPhase.Preview>(vm.phase.value).selected.isNotEmpty())
        startRecreate()
        vm.confirm()
        assertEquals(CleanupPhase.Error(RECREATE_IN_PROGRESS_MESSAGE), vm.phase.value)
        advanceUntilIdle()
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun containerCleanupWorksAgainOnceTheRecreateFinished() = runTest {
        val key = startRecreate()
        val sessions = env().container.recreateSessions
        recreateGate.complete(Unit)
        settle { sessions.progress(key).value?.result != null }
        assertFalse(sessions.isRunning("s1", 1))
        assertIs<CleanupPhase.Preview>(preview(CleanupKind.Containers).phase.value)
    }

    @Test
    fun aRunThatFinishesAfterTheSheetClosedReopensIt() = runTest {
        deleteGate = CompletableDeferred()
        val vm = preview(CleanupKind.Networks)
        assertTrue(vm.sheetVisible.value)
        vm.confirm()
        settle { vm.phase.value is CleanupPhase.Running }
        vm.acknowledge() // closed while running
        assertFalse(vm.sheetVisible.value)
        deleteGate!!.complete(Unit)
        settle { vm.phase.value is CleanupPhase.Done }
        settle { vm.sheetVisible.value }
        assertEquals(2, (vm.phase.value as CleanupPhase.Done).deleted)

        vm.acknowledge()
        assertFalse(vm.sheetVisible.value)
        settle { vm.phase.value == CleanupPhase.Idle }
        assertFalse(vm.sheetVisible.value)
    }
}
