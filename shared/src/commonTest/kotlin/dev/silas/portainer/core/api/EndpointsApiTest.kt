package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.EndpointType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EndpointsApiTest {
    private val key = "secret-key-123"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "[]",
    ) = PortainerClient(
        "https://portainer.example.com",
        key,
        MockEngine { request ->
            respond(body, status, json)
        },
    )

    @Test
    fun sendsApiKeyHeader() = runTest {
        var header: String? = null
        var url: String? = null
        val c = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                header = request.headers["X-API-Key"]
                url = request.url.toString()
                respond("[]", HttpStatusCode.OK, json)
            },
        )
        c.endpoints()
        assertEquals("k", header)
        assertEquals("https://h/api/endpoints", url)
    }

    @Test
    fun parsesEndpoints() = runTest {
        val c = client(
            body = """[{"Id":1,"Name":"local","Type":1,"Status":1,"URL":"unix:///var/run/docker.sock"},
                {"Id":3,"Name":"edge","Type":4,"Status":2}]""",
        )
        val list = c.endpoints()
        assertEquals(2, list.size)
        assertEquals("local", list[0].name)
        assertTrue(list[0].online)
        assertTrue(list[0].type.supported)
        assertEquals(EndpointType.EdgeAgent, list[1].type)
        assertFalse(list[1].type.supported)
        assertFalse(list[1].online)
    }

    @Test
    fun parsesStatus() = runTest {
        val c = client(body = """{"Version":"2.21.0","InstanceID":"x"}""")
        assertEquals("2.21.0", c.status().version)
    }

    @Test
    fun mapsUnauthorized() = runTest {
        val c = client(HttpStatusCode.Unauthorized, """{"message":"nope"}""")
        assertFailsWith<PortainerException.Unauthorized> { c.endpoints() }
    }

    @Test
    fun mapsNotFound() = runTest {
        val c = client(HttpStatusCode.NotFound, """{"message":"gone"}""")
        assertIs<PortainerException.NotFound>(assertFailsWith<PortainerException> { c.endpoints() })
    }

    @Test
    fun mapsApiErrorMessage() = runTest {
        val c = client(HttpStatusCode.InternalServerError, """{"message":"boom","details":"x"}""")
        val e = assertFailsWith<PortainerException.Api> { c.endpoints() }
        assertEquals(500, e.status)
        assertTrue(e.message!!.contains("boom"))
        assertTrue(e.message!!.contains("x"))
    }

    @Test
    fun apiErrorFallsBackToStatusText() = runTest {
        val c = client(HttpStatusCode.BadGateway, "<html>")
        val e = assertFailsWith<PortainerException.Api> { c.endpoints() }
        assertEquals(502, e.status)
        assertTrue(e.message!!.contains("Bad Gateway"))
    }

    @Test
    fun mapsConnectionFailureToUnreachable() = runTest {
        val c = PortainerClient("https://h", key, MockEngine { throw RuntimeException("connect failed to https://h?key=$key") })
        val e = assertFailsWith<PortainerException.Unreachable> { c.endpoints() }
        assertFalse(e.message!!.contains(key))
    }

    @Test
    fun errorNeverContainsKey() = runTest {
        val cases = listOf(
            HttpStatusCode.Unauthorized to """{"message":"bad $key"}""",
            HttpStatusCode.NotFound to "",
            HttpStatusCode.InternalServerError to """{"message":"boom"}""",
        )
        for ((status, body) in cases) {
            val e = assertFailsWith<PortainerException> { client(status, body).endpoints() }
            assertFalse(e.message!!.contains(key), "message leaked key: ${e.message}")
        }
    }

    @Test
    fun dockerPathBuildsProxyPath() {
        assertEquals("/api/endpoints/2/docker/containers/json", dockerPath(2, "containers/json"))
    }
}
