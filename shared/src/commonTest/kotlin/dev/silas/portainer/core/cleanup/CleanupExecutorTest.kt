package dev.silas.portainer.core.cleanup

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CleanupExecutorTest {
    private val a = CleanupItem("a", "a", 100)
    private val b = CleanupItem("b", "b", 50)
    private val c = CleanupItem("c", "c", 7)
    private val d = CleanupItem("d", "d", null)

    @Test
    fun failureDoesNotStopTheRest() = runTest {
        val attempts = mutableListOf<String>()
        val out = CleanupExecutor {
            attempts += it.id
            if (it.id == "b") throw PortainerException.Api(409, "in use")
        }.run(listOf(a, b, c)).toList()
        assertEquals(listOf("a", "b", "c"), attempts)
        assertEquals(
            listOf(
                CleanupProgress.Deleted(a),
                CleanupProgress.Failed(b, "in use"),
                CleanupProgress.Deleted(c),
                CleanupProgress.Finished(2, 1, 107),
            ),
            out,
        )
    }

    @Test
    fun notFoundIsAlreadyRemovedAndNullSizeCountsZero() = runTest {
        val out = CleanupExecutor {
            if (it.id == "a") throw PortainerException.NotFound("gone")
        }.run(listOf(a, d)).toList()
        assertEquals(
            listOf(CleanupProgress.Failed(a, "Already removed"), CleanupProgress.Deleted(d), CleanupProgress.Finished(1, 1, 0)),
            out,
        )
    }

    @Test
    fun cancellationIsRethrown() = runTest {
        assertFailsWith<CancellationException> {
            CleanupExecutor { throw CancellationException("stop") }.run(listOf(a)).toList()
        }
    }

    @Test
    fun deleterUsesTheRightCallAndNeverForces() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val client = PortainerClient("https://h", "test-api-key", MockEngine { seen += it; respond("", HttpStatusCode.NoContent) })
        for (kind in CleanupKind.entries) kind.deleter(client, 4)(CleanupItem("x", "x", null))
        assertEquals(
            listOf(
                "/api/endpoints/4/docker/containers/x",
                "/api/endpoints/4/docker/images/x",
                "/api/endpoints/4/docker/volumes/x",
                "/api/endpoints/4/docker/networks/x",
            ),
            seen.map { it.url.encodedPath },
        )
        seen.forEach {
            assertEquals(HttpMethod.Delete, it.method)
            assertFalse(it.url.parameters["force"] == "true", "force must never be sent")
        }
    }
}
