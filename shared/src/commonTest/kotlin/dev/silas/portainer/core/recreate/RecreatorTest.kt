package dev.silas.portainer.core.recreate

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.recreate.RecreateEvent.Failed
import dev.silas.portainer.core.recreate.RecreateEvent.StepDone
import dev.silas.portainer.core.recreate.RecreateEvent.StepStarted
import dev.silas.portainer.core.recreate.RecreateEvent.Succeeded
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OLD = "old-id"
private const val NEW = "new-id"
private const val NOW = 1_760_000_000L
private const val TMP = "web-old-$NOW"

/** Records every call as a string; a call whose string is in [failures] throws the mapped exception. */
private class FakeOps(private val failures: Map<String, Throwable> = emptyMap()) : DockerOps {
    val calls = mutableListOf<String>()

    private fun record(call: String) {
        calls += call
        failures[call]?.let { throw it }
    }

    override suspend fun pull(image: ImageRef, registryId: Int?) = record("pull $image $registryId")
    override suspend fun rename(id: String, newName: String) = record("rename $id $newName")
    override suspend fun create(name: String, body: JsonObject): String {
        record("create $name")
        return NEW
    }
    override suspend fun connect(network: String, containerId: String, config: JsonObject) =
        record("connect $network $containerId")
    override suspend fun stop(id: String) = record("stop $id")
    override suspend fun start(id: String) = record("start $id")
    override suspend fun remove(id: String, force: Boolean) = record("remove $id force=$force")
}

private fun plan(
    wasRunning: Boolean = true,
    pull: Boolean = true,
    extraNetworks: Map<String, JsonObject> = mapOf("backend" to JsonObject(mapOf("IPAMConfig" to JsonObject(mapOf("IPv4Address" to JsonPrimitive("172.20.0.10")))))),
    warnings: List<RecreateWarning> = listOf(RecreateWarning.ConnectionDependency),
) = RecreatePlan(
    containerName = "web",
    wasRunning = wasRunning,
    createBody = JsonObject(mapOf("Image" to JsonPrimitive("nginx:1.27"))),
    extraNetworks = extraNetworks,
    image = ImageRef.parse("nginx:1.27"),
    pull = pull,
    registryId = 3,
    changes = ChangeSummary(null, emptyList(), emptyList(), emptyList(), emptyList()),
    warnings = warnings,
)

private val boom = PortainerException.Api(500, "boom")

class RecreatorTest {
    private fun recreator(ops: DockerOps) = Recreator(ops) { NOW }

    @Test
    fun happyPathCallOrder() = runTest {
        val ops = FakeOps()
        val events = recreator(ops).run(OLD, plan()).toList()
        assertEquals(
            listOf(
                "pull nginx:1.27 3",
                "rename $OLD $TMP",
                "create web",
                "connect backend $NEW",
                "stop $OLD",
                "start $NEW",
                "remove $OLD force=true",
            ),
            ops.calls,
        )
        val steps = RecreateStep.entries
        assertEquals(steps.flatMap { listOf(StepStarted(it), StepDone(it)) } + Succeeded(NEW), events)
    }

    @Test
    fun stoppedContainerIsNotStarted() = runTest {
        val ops = FakeOps()
        val events = recreator(ops).run(OLD, plan(wasRunning = false, pull = false, extraNetworks = emptyMap())).toList()
        assertEquals(listOf("rename $OLD $TMP", "create web", "remove $OLD force=true"), ops.calls)
        val steps = listOf(RecreateStep.RenameOld, RecreateStep.CreateNew, RecreateStep.RemoveOld)
        assertEquals(steps.flatMap { listOf(StepStarted(it), StepDone(it)) } + Succeeded(NEW), events)
    }

    @Test
    fun pullFailureTouchesNothing() = runTest {
        val ops = FakeOps(mapOf("pull nginx:1.27 3" to boom))
        val events = recreator(ops).run(OLD, plan()).toList()
        assertEquals(listOf("pull nginx:1.27 3"), ops.calls)
        assertEquals(listOf(StepStarted(RecreateStep.Pull), Failed(RecreateStep.Pull, "boom", rolledBack = true)), events)
    }

    @Test
    fun renameFailureNeedsNoRollback() = runTest {
        val ops = FakeOps(mapOf("rename $OLD $TMP" to boom))
        val events = recreator(ops).run(OLD, plan(pull = false)).toList()
        assertEquals(listOf("rename $OLD $TMP"), ops.calls)
        assertEquals(Failed(RecreateStep.RenameOld, "boom", rolledBack = true), events.last())
    }

    @Test
    fun createFailureRenamesOldBackWithoutStopping() = runTest {
        val ops = FakeOps(mapOf("create web" to boom))
        val events = recreator(ops).run(OLD, plan()).toList()
        assertEquals(listOf("pull nginx:1.27 3", "rename $OLD $TMP", "create web", "rename $OLD web"), ops.calls)
        assertEquals(Failed(RecreateStep.CreateNew, "boom", rolledBack = true), events.last())
        assertEquals(StepStarted(RecreateStep.CreateNew), events[events.size - 2])
    }

    @Test
    fun connectFailureRemovesNewAndRenamesBackWithoutStopping() = runTest {
        val ops = FakeOps(mapOf("connect backend $NEW" to boom))
        val events = recreator(ops).run(OLD, plan()).toList()
        assertEquals(
            listOf("pull nginx:1.27 3", "rename $OLD $TMP", "create web", "connect backend $NEW", "remove $NEW force=true", "rename $OLD web"),
            ops.calls,
        )
        assertEquals(Failed(RecreateStep.ConnectNetworks, "boom", rolledBack = true), events.last())
    }

    @Test
    fun startFailureRestoresAndRestartsOld() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom))
        val events = recreator(ops).run(OLD, plan()).toList()
        assertEquals(
            listOf(
                "pull nginx:1.27 3", "rename $OLD $TMP", "create web", "connect backend $NEW", "stop $OLD", "start $NEW",
                "remove $NEW force=true", "rename $OLD web", "start $OLD",
            ),
            ops.calls,
        )
        assertEquals(Failed(RecreateStep.StartNew, "boom", rolledBack = true), events.last())
    }

    @Test
    fun stopFailureRestoresOld() = runTest {
        val ops = FakeOps(mapOf("stop $OLD" to boom))
        val events = recreator(ops).run(OLD, plan(pull = false, extraNetworks = emptyMap())).toList()
        assertEquals(
            listOf("rename $OLD $TMP", "create web", "stop $OLD", "remove $NEW force=true", "rename $OLD web", "start $OLD"),
            ops.calls,
        )
        assertEquals(Failed(RecreateStep.StopOld, "boom", rolledBack = true), events.last())
    }

    @Test
    fun autoRemoveStartFailureReportsNoRollback() = runTest {
        val ops = FakeOps(
            mapOf(
                "start $NEW" to boom,
                "rename $OLD web" to PortainerException.NotFound("Not found: No such container: $OLD"),
            ),
        )
        val events = recreator(ops).run(OLD, plan(warnings = listOf(RecreateWarning.AutoRemove, RecreateWarning.ConnectionDependency))).toList()
        assertEquals(
            Failed(
                RecreateStep.StartNew,
                "boom. The old container was removed by Docker (auto-remove), so it can't be restored.",
                rolledBack = false,
            ),
            events.last(),
        )
        assertFalse("start $OLD" in ops.calls, "a container Docker already removed can't be started")
    }

    @Test
    fun everyRollbackStepIsAttemptedAndLeftoversNamed() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom, "rename $OLD web" to PortainerException.Api(409, "conflict")))
        val events = recreator(ops).run(OLD, plan(pull = false, extraNetworks = emptyMap())).toList()
        assertEquals("start $OLD", ops.calls.last())
        val failed = events.last() as Failed
        assertEquals(RecreateStep.StartNew, failed.step)
        assertFalse(failed.rolledBack)
        assertTrue(failed.message.startsWith("boom. "), failed.message)
        assertTrue(failed.message.contains("Renaming the original container back failed; it is named $TMP"), failed.message)
    }

    @Test
    fun removeOldFailureReported() = runTest {
        val ops = FakeOps(mapOf("remove $OLD force=true" to boom))
        val events = recreator(ops).run(OLD, plan()).toList()
        assertEquals("remove $OLD force=true", ops.calls.last())
        assertEquals(StepStarted(RecreateStep.RemoveOld), events[events.size - 2])
        assertEquals(Failed(RecreateStep.RemoveOld, "boom", rolledBack = false), events.last())
        assertFalse(events.any { it is Succeeded })
    }

    @Test
    fun oldAlreadyRemovedByDockerCountsAsRemoved() = runTest {
        val ops = FakeOps(mapOf("remove $OLD force=true" to PortainerException.NotFound("Not found")))
        val events = recreator(ops).run(OLD, plan(warnings = listOf(RecreateWarning.AutoRemove))).toList()
        assertEquals(listOf(StepDone(RecreateStep.RemoveOld), Succeeded(NEW)), events.takeLast(2))
    }

    @Test
    fun portainerOpsNeverRemoveVolumes() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val client = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                requests += request.method to request.url.toString()
                when {
                    request.url.encodedPath.endsWith("/images/create") -> respond("{\"status\":\"done\"}\n", HttpStatusCode.OK)
                    request.url.encodedPath.endsWith("/containers/create") ->
                        respond("{\"Id\":\"$NEW\",\"Warnings\":[]}", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                    else -> respond("", HttpStatusCode.NoContent)
                }
            },
        )
        val events = Recreator(PortainerDockerOps(client, 2)) { NOW }.run(OLD, plan()).toList()
        assertEquals(Succeeded(NEW), events.last())
        val removeOld = requests.single { it.first == HttpMethod.Delete }
        assertEquals("https://h/api/endpoints/2/docker/containers/$OLD?force=true", removeOld.second)
        assertFalse(requests.any { "v=" in it.second.substringAfter('?', "") })
    }
}
