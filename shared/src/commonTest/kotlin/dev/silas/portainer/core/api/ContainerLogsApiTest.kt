package dev.silas.portainer.core.api

import dev.silas.portainer.core.logs.LogOptions
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ContainerLogsApiTest {
    @Test
    fun unauthorizedMapsToUnauthorized() = runTest {
        val c = PortainerClient("https://h", "k", MockEngine { respond("", HttpStatusCode.Unauthorized) })
        assertFailsWith<PortainerException.Unauthorized> { c.containerLogs(1, "abc", LogOptions()).toList() }
    }

    @Test
    fun ioFailureMapsToUnreachableWithoutKey() = runTest {
        val key = "secret-key-123"
        val c = PortainerClient("https://h", key, MockEngine { throw IOException("boom $key") })
        val e = assertFailsWith<PortainerException.Unreachable> { c.containerLogs(1, "abc", LogOptions()).toList() }
        assertFalse(e.message.orEmpty().contains(key))
        assertEquals("Could not reach Portainer", e.message)
    }
}
