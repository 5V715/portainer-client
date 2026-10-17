package dev.silas.portainer.core.api

import dev.silas.portainer.core.Fixtures
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.Health
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ContainersApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun parsesSummaries() = runTest {
        var url: String? = null
        val c = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                url = request.url.toString()
                respond(Fixtures.CONTAINERS, HttpStatusCode.OK, json)
            },
        )
        val list = c.containers(2)
        assertEquals("https://h/api/endpoints/2/docker/containers/json?all=1", url)
        assertEquals(listOf("web", "api", "old-batch", "media-jellyfin-1"), list.map { it.name })
        assertEquals(Health.Healthy, list[0].health)
        assertEquals(ContainerState.Running, list[0].state)
        assertEquals(Health.Unhealthy, list[1].health)
        assertEquals(emptyMap(), list[1].labels)
        assertEquals(ContainerState.Exited, list[2].state)
        assertNull(list[2].health)
        assertNull(list[2].stack)
        assertEquals("media", list[3].stack)
        assertEquals(Health.Starting, list[3].health)
    }

    @Test
    fun startTreats304AsSuccess() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val c = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                method = request.method
                url = request.url.toString()
                respond("", HttpStatusCode.NotModified)
            },
        )
        c.startContainer(1, "abc")
        c.stopContainer(1, "abc")
        assertEquals(HttpMethod.Post, method)
        assertEquals("https://h/api/endpoints/1/docker/containers/abc/stop", url)
    }

    @Test
    fun actionErrorsStillFail() = runTest {
        val c = PortainerClient("https://h", "k", MockEngine { respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json) })
        assertFailsWith<PortainerException.Api> { c.restartContainer(1, "abc") }
    }

    @Test
    fun removeSendsForceFlag() = runTest {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val c = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                seen += request.method to request.url.toString()
                respond("", HttpStatusCode.NoContent)
            },
        )
        c.removeContainer(1, "abc", force = true)
        c.removeContainer(1, "abc")
        assertEquals(HttpMethod.Delete to "https://h/api/endpoints/1/docker/containers/abc?force=true", seen[0])
        assertEquals(HttpMethod.Delete to "https://h/api/endpoints/1/docker/containers/abc", seen[1])
    }
}
