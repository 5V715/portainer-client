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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
private const val FAILED = "web-failed-$NOW"

/**
 * Records every call as a string; a call whose string is in [failures] throws the mapped exception, and one in
 * [gates] suspends until its gate completes.
 */
private class FakeOps(
    private val failures: Map<String, Throwable> = emptyMap(),
    private val gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
) : DockerOps {
    val calls = mutableListOf<String>()

    private suspend fun record(call: String) {
        calls += call
        gates[call]?.await()
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
    containerName: String = "web",
) = RecreatePlan(
    containerName = containerName,
    wasRunning = wasRunning,
    createBody = JsonObject(mapOf("Image" to JsonPrimitive("nginx:1.27"))),
    extraNetworks = extraNetworks,
    image = ImageRef.parse("nginx:1.27"),
    pull = pull,
    registryId = 3,
    changes = ChangeSummary(null, emptyList(), emptyList(), emptyList(), emptyList()),
    warnings = warnings,
    originalName = "web",
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

    // ---- Name edited: the new container is "api", the original stays "web" ----

    @Test
    fun renamedReplaceSucceedsUnderTheNewName() = runTest {
        val ops = FakeOps()
        val events = recreator(ops).run(OLD, plan(containerName = "api")).toList()
        assertEquals(
            listOf("pull nginx:1.27 3", "rename $OLD $TMP", "create api", "connect backend $NEW", "stop $OLD", "start $NEW", "remove $OLD force=true"),
            ops.calls,
        )
        assertEquals(Succeeded(NEW), events.last())
    }

    @Test
    fun renamedReplaceCreateFailureRestoresTheOriginalName() = runTest {
        val ops = FakeOps(mapOf("create api" to boom))
        val events = recreator(ops).run(OLD, plan(containerName = "api")).toList()
        assertEquals(listOf("pull nginx:1.27 3", "rename $OLD $TMP", "create api", "rename $OLD web"), ops.calls)
        assertEquals(Failed(RecreateStep.CreateNew, "boom", rolledBack = true), events.last())
    }

    @Test
    fun renamedReplaceStartFailureRestoresTheOriginalName() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom))
        val events = recreator(ops).run(OLD, plan(containerName = "api")).toList()
        assertEquals(
            listOf(
                "pull nginx:1.27 3", "rename $OLD $TMP", "create api", "connect backend $NEW", "stop $OLD", "start $NEW",
                "rename $OLD web", "start $OLD", "remove $NEW force=true",
            ),
            ops.calls,
        )
        assertEquals(Failed(RecreateStep.StartNew, "boom", rolledBack = true), events.last())
    }

    @Test
    fun renamedReplaceRollbackNeverRenamesTheNewContainerAside() = runTest {
        // The new container doesn't hold the original's name, so the original goes straight back to it.
        val failures = mapOf("start $NEW" to boom, "rename $NEW $FAILED" to boom)
        val ops = FakeOps(failures)
        val events = recreator(ops).run(OLD, plan(containerName = "api")).toList()
        assertFalse(ops.calls.any { it.startsWith("rename $NEW") }, "${ops.calls}")
        assertEquals(listOf("start $NEW", "rename $OLD web", "start $OLD", "remove $NEW force=true"), ops.calls.takeLast(4))
        assertEquals(Failed(RecreateStep.StartNew, "boom", rolledBack = true), events.last())

        val stopOps = FakeOps(mapOf("stop $OLD" to boom, "rename $NEW $FAILED" to boom))
        val stopEvents = recreator(stopOps).run(OLD, plan(containerName = "api", pull = false, extraNetworks = emptyMap())).toList()
        assertEquals(listOf("rename $OLD $TMP", "create api", "stop $OLD", "rename $OLD web", "start $OLD", "remove $NEW force=true"), stopOps.calls)
        assertEquals(Failed(RecreateStep.StopOld, "boom", rolledBack = true), stopEvents.last())
    }

    @Test
    fun renamedReplaceStartOldFailureKeepsTheNewContainerUnderItsName() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom, "start $OLD" to PortainerException.Api(500, "port in use")))
        val failed = recreator(ops).run(OLD, plan(containerName = "api", pull = false, extraNetworks = emptyMap())).toList().last() as Failed
        assertFalse(ops.calls.any { it.startsWith("remove") })
        assertEquals(
            "boom. Starting the original container failed; it is stopped and named web. The new container was kept as api (not running).",
            failed.message,
        )
    }

    @Test
    fun renamedReplaceAutoRemovedOriginalKeepsNewUnderTheNewName() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom, "rename $OLD web" to PortainerException.NotFound("gone")))
        val events = recreator(ops).run(OLD, plan(containerName = "api")).toList()
        assertEquals(listOf("start $NEW", "rename $OLD web"), ops.calls.takeLast(2))
        assertFalse(ops.calls.any { it.startsWith("rename $NEW") || it.startsWith("remove $NEW") }, "${ops.calls}")
        assertTrue((events.last() as Failed).message.endsWith("The new container was kept as api (not running) so you can fix and start it."))
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
    fun startFailureRenamesNewAsideThenRestoresAndRestartsOld() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom))
        val events = recreator(ops).run(OLD, plan()).toList()
        assertEquals(
            listOf(
                "pull nginx:1.27 3", "rename $OLD $TMP", "create web", "connect backend $NEW", "stop $OLD", "start $NEW",
                "rename $NEW $FAILED", "rename $OLD web", "start $OLD", "remove $NEW force=true",
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
            listOf("rename $OLD $TMP", "create web", "stop $OLD", "rename $NEW $FAILED", "rename $OLD web", "start $OLD", "remove $NEW force=true"),
            ops.calls,
        )
        assertEquals(Failed(RecreateStep.StopOld, "boom", rolledBack = true), events.last())
    }

    private val autoRemoved = PortainerException.NotFound("Not found: No such container: $OLD")
    private val autoRemovePlan = plan(warnings = listOf(RecreateWarning.AutoRemove, RecreateWarning.ConnectionDependency))

    @Test
    fun autoRemoveStartFailureKeepsNewContainer() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom, "rename $OLD web" to autoRemoved))
        val events = recreator(ops).run(OLD, autoRemovePlan).toList()
        assertEquals(
            listOf("stop $OLD", "start $NEW", "rename $NEW $FAILED", "rename $OLD web", "rename $NEW web"),
            ops.calls.takeLast(5),
        )
        assertFalse(ops.calls.any { it.startsWith("remove $NEW") }, "the only remaining container must not be removed")
        assertFalse("start $OLD" in ops.calls, "a container Docker already removed can't be started")
        assertEquals(
            Failed(
                RecreateStep.StartNew,
                "boom. The old container was removed by Docker (auto-remove), so it can't be restored. " +
                    "The new container was kept as web (not running) so you can fix and start it.",
                rolledBack = false,
            ),
            events.last(),
        )
    }

    @Test
    fun autoRemoveKeptContainerNamedAsideWhenRenameBackFails() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom, "rename $OLD web" to autoRemoved, "rename $NEW web" to boom))
        val failed = recreator(ops).run(OLD, autoRemovePlan).toList().last() as Failed
        assertFalse(ops.calls.any { it.startsWith("remove $NEW") })
        assertFalse(failed.rolledBack)
        assertTrue(failed.message.endsWith("The new container was kept as $FAILED (not running) so you can fix and start it."), failed.message)
    }

    @Test
    fun autoRemoveOldVanishingAfterRenameBackKeepsNewContainer() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom, "start $OLD" to autoRemoved))
        val failed = recreator(ops).run(OLD, autoRemovePlan).toList().last() as Failed
        assertEquals(listOf("rename $NEW $FAILED", "rename $OLD web", "start $OLD", "rename $NEW web"), ops.calls.takeLast(4))
        assertFalse(ops.calls.any { it.startsWith("remove $NEW") }, "the only remaining container must not be removed")
        assertEquals(RecreateStep.StartNew, failed.step)
        assertFalse(failed.rolledBack)
        assertEquals(
            "boom. The old container was removed by Docker (auto-remove), so it can't be restored. " +
                "The new container was kept as web (not running) so you can fix and start it.",
            failed.message,
        )
    }

    @Test
    fun startOldFailureKeepsBothContainers() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom, "start $OLD" to PortainerException.Api(500, "port in use")))
        val failed = recreator(ops).run(OLD, plan(pull = false, extraNetworks = emptyMap())).toList().last() as Failed
        assertEquals(listOf("rename $NEW $FAILED", "rename $OLD web", "start $OLD"), ops.calls.takeLast(3))
        assertFalse(ops.calls.any { it.startsWith("remove") })
        assertFalse(failed.rolledBack)
        assertEquals(
            "boom. Starting the original container failed; it is stopped and named web. " +
                "The new container was kept as $FAILED (not running).",
            failed.message,
        )
    }

    @Test
    fun asideRenameFailureRemovesNothing() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom, "rename $NEW $FAILED" to boom))
        val failed = recreator(ops).run(OLD, autoRemovePlan).toList().last() as Failed
        assertEquals(listOf("start $NEW", "rename $NEW $FAILED"), ops.calls.takeLast(2))
        assertFalse(ops.calls.any { it.startsWith("remove") })
        assertFalse(failed.rolledBack)
        assertEquals(
            "boom. Renaming the new container aside failed. Nothing was removed: " +
                "the new container is web (not started) and the original is $TMP.",
            failed.message,
        )
    }

    @Test
    fun lostConnectionDuringStopDoesNothingDestructive() = runTest {
        val ops = FakeOps(mapOf("stop $OLD" to PortainerException.Unreachable("Could not reach Portainer")))
        val events = recreator(ops).run(OLD, plan(pull = false, extraNetworks = emptyMap())).toList()
        assertEquals(listOf("rename $OLD $TMP", "create web", "stop $OLD"), ops.calls)
        assertEquals(
            Failed(
                RecreateStep.StopOld,
                "Lost connection while stopping the old container. Nothing was removed: " +
                    "the original is $TMP, the new one is web (not started).",
                rolledBack = false,
            ),
            events.last(),
        )
    }

    @Test
    fun nonPortainerExceptionIsRolledBack() = runTest {
        val ops = FakeOps(mapOf("create web" to IllegalStateException("bad body")))
        val events = recreator(ops).run(OLD, plan(pull = false)).toList()
        assertEquals(listOf("rename $OLD $TMP", "create web", "rename $OLD web"), ops.calls)
        assertEquals(Failed(RecreateStep.CreateNew, "bad body", rolledBack = true), events.last())
    }

    @Test
    fun opCancelledWhileCollectorActiveIsRolledBack() = runTest {
        // e.g. the HTTP engine was closed under the recreate: the op throws CancellationException, the flow is not cancelled.
        val ops = FakeOps(mapOf("create web" to CancellationException("Engine was closed")))
        val events = recreator(ops).run(OLD, plan(pull = false)).toList()
        assertEquals(listOf("rename $OLD $TMP", "create web", "rename $OLD web"), ops.calls)
        assertEquals(Failed(RecreateStep.CreateNew, "Lost connection to Portainer", rolledBack = true), events.last())
    }

    @Test
    fun cancellingTheCollectorMidStepStopsWithoutRollback() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ops = FakeOps(gates = mapOf("create web" to gate))
        val events = mutableListOf<RecreateEvent>()
        val job = launch { recreator(ops).run(OLD, plan(pull = false)).collect { events += it } }
        while (ops.calls.lastOrNull() != "create web") yield()
        job.cancel()
        job.join()
        assertEquals(listOf("rename $OLD $TMP", "create web"), ops.calls)
        assertFalse(events.any { it is Failed })
    }

    @Test
    fun cancellingDuringRollbackStillCompletesIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ops = FakeOps(failures = mapOf("start $NEW" to boom), gates = mapOf("rename $NEW $FAILED" to gate))
        val events = mutableListOf<RecreateEvent>()
        val job = launch { recreator(ops).run(OLD, plan(pull = false, extraNetworks = emptyMap())).collect { events += it } }
        while (ops.calls.lastOrNull() != "rename $NEW $FAILED") yield() // rollback is now suspended in its first op
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("rename $NEW $FAILED", "rename $OLD web", "start $OLD", "remove $NEW force=true"), ops.calls.takeLast(4))
        assertFalse(events.any { it is Failed }, "a cancelled collector gets no terminal event")
    }

    @Test
    fun everyRollbackStepIsAttemptedAndLeftoversNamed() = runTest {
        val ops = FakeOps(mapOf("start $NEW" to boom, "rename $OLD web" to PortainerException.Api(409, "conflict")))
        val events = recreator(ops).run(OLD, plan(pull = false, extraNetworks = emptyMap())).toList()
        assertEquals(listOf("rename $NEW $FAILED", "rename $OLD web", "start $OLD"), ops.calls.takeLast(3))
        assertFalse(ops.calls.any { it.startsWith("remove") }, "the old container isn't confirmed back, so the new one stays")
        val failed = events.last() as Failed
        assertEquals(RecreateStep.StartNew, failed.step)
        assertFalse(failed.rolledBack)
        assertTrue(failed.message.startsWith("boom. "), failed.message)
        assertEquals(
            "boom. Renaming the original container back failed; it is named $TMP. The new container was kept as $FAILED (not running).",
            failed.message,
        )
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
    fun removalAlreadyInProgressCountsAsRemoved() = runTest {
        val ops = FakeOps(mapOf("remove $OLD force=true" to PortainerException.Api(409, "removal of container $OLD is already in progress")))
        val events = recreator(ops).run(OLD, plan(warnings = listOf(RecreateWarning.AutoRemove))).toList()
        assertEquals(listOf(StepDone(RecreateStep.RemoveOld), Succeeded(NEW)), events.takeLast(2))
    }

    @Test
    fun otherConflictOnRemoveOldIsReported() = runTest {
        val ops = FakeOps(mapOf("remove $OLD force=true" to PortainerException.Api(409, "conflict")))
        val events = recreator(ops).run(OLD, plan()).toList()
        assertEquals(Failed(RecreateStep.RemoveOld, "conflict", rolledBack = false), events.last())
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
