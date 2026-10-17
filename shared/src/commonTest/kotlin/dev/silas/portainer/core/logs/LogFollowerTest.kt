package dev.silas.portainer.core.logs

import dev.silas.portainer.core.api.PortainerClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogFollowerTest {
    private val t1 = "2026-10-17T10:00:00.000000001Z"
    private val t2 = "2026-10-17T10:00:05.500000000Z"
    private val t3 = "2026-10-17T10:00:09.000000000Z"

    @Test
    fun pollingUsesSinceAndDropsBoundaryDuplicates() = runTest {
        val urls = mutableListOf<Url>()
        val engine = MockEngine { request ->
            urls += request.url
            val body = when (urls.size) {
                1 -> "$t1 a\n$t2 b\n"
                else -> "$t2 b\n$t3 c\n"
            }
            respond(frame(1, body), HttpStatusCode.OK)
        }
        val client = PortainerClient("https://h", "k", engine)
        val batches = followLogs(
            client, 1, "abc", tty = false, options = LogOptions(tail = 50),
            streaming = false, pollIntervalMs = 10,
        ).take(2).toList()

        assertEquals(listOf("a", "b"), batches[0].map { it.text })
        assertEquals(listOf("c"), batches[1].map { it.text })
        assertEquals("50", urls[0].parameters["tail"])
        assertEquals("false", urls[0].parameters["follow"])
        assertEquals(null, urls[0].parameters["since"])
        // 2026-10-17T10:00:05Z
        assertEquals("1792231205", urls[1].parameters["since"])
        assertEquals("false", urls[1].parameters["follow"])
    }

    @Test
    fun streamingEmitsDecodedBatchesFromOneFollowRequest() = runTest {
        val urls = mutableListOf<Url>()
        val engine = MockEngine { request ->
            urls += request.url
            respond(frame(1, "$t1 a\n") + frame(2, "$t2 b\n"), HttpStatusCode.OK)
        }
        val client = PortainerClient("https://h", "k", engine)
        val lines = followLogs(client, 1, "abc", tty = false, options = LogOptions(), streaming = true)
            .toList().flatten()
        assertEquals(listOf(LogStream.Stdout to "a", LogStream.Stderr to "b"), lines.map { it.stream to it.text })
        assertEquals(1, urls.size)
        assertEquals("true", urls[0].parameters["follow"])
        assertEquals("200", urls[0].parameters["tail"])
    }

    @Test
    fun epochSecondsParsesRfc3339() {
        assertEquals(1792231205L, rfc3339EpochSeconds(t2))
        assertEquals(null, rfc3339EpochSeconds("2026-13-xxT10:00:00Z"))
        assertEquals(null, rfc3339EpochSeconds("2026-10-17"))
        assertEquals(0L, rfc3339EpochSeconds("1970-01-01T00:00:00Z"))
        assertEquals(1792231205L, rfc3339EpochSeconds("2026-10-17T12:00:05+02:00"))
    }

    @Test
    fun tailZeroWithSilentContainerNeverRequestsAllHistory() = runTest {
        val urls = mutableListOf<Url>()
        val engine = MockEngine { request ->
            urls += request.url
            respond(if (urls.size == 1) ByteArray(0) else frame(1, "$t1 a\n"), HttpStatusCode.OK)
        }
        val client = PortainerClient("https://h", "k", engine)
        val batches = followLogs(
            client, 1, "abc", tty = false, options = LogOptions(tail = 0),
            streaming = false, pollIntervalMs = 10,
        ).take(1).toList()
        assertEquals(listOf("a"), batches.single().map { it.text })
        assertEquals(2, urls.size)
        urls.forEach { assertTrue(it.parameters["tail"] == "0" || it.parameters["since"] != null, it.toString()) }
    }
}
