package dev.silas.portainer.core.api

import dev.silas.portainer.core.Fixtures
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.Health
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun parsesImageIdVolumesAndNetworks() = runTest {
        val c = PortainerClient("https://h", "k", MockEngine { respond(Fixtures.CONTAINERS, HttpStatusCode.OK, json) })
        val list = c.containers(2)
        assertEquals("sha256:a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90", list[0].imageId)
        assertEquals(emptyList(), list[0].volumeNames)
        assertEquals(listOf("bridge"), list[0].networkNames)
        assertEquals(listOf("api-data"), list[1].volumeNames)
        assertEquals(listOf("bridge", "backend"), list[1].networkNames)
        assertEquals(listOf("cache"), list[2].volumeNames)
        assertEquals(emptyList(), list[3].volumeNames) // bind mount ignored
        assertEquals(listOf("media_default"), list[3].networkNames)
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

    private fun bodyText(content: OutgoingContent): String = (content as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test
    fun createPostsBodyAndReturnsId() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        var body: String? = null
        var contentType: String? = null
        val c = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                method = request.method
                url = request.url.toString()
                body = bodyText(request.body)
                contentType = request.body.contentType?.toString()
                respond("""{"Id":"new123","Warnings":[]}""", HttpStatusCode.Created, json)
            },
        )
        val createBody = JsonObject(mapOf("Image" to JsonPrimitive("nginx:1.27")))
        val id = c.createContainer(1, "web", createBody)
        assertEquals("new123", id)
        assertEquals(HttpMethod.Post, method)
        assertEquals("https://h/api/endpoints/1/docker/containers/create?name=web", url)
        assertEquals(createBody, Json.parseToJsonElement(body!!))
        assertEquals("application/json", contentType?.substringBefore(';'))
    }

    @Test
    fun createConflictFails() = runTest {
        val c = PortainerClient("https://h", "k", MockEngine { respond("""{"message":"name in use"}""", HttpStatusCode.Conflict, json) })
        val e = assertFailsWith<PortainerException.Api> { c.createContainer(1, "web", JsonObject(emptyMap())) }
        assertEquals(409, e.status)
        assertEquals("name in use", e.message)
    }

    @Test
    fun renamePostsNewName() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val c = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                method = request.method
                url = request.url.toString()
                respond("", HttpStatusCode.NoContent)
            },
        )
        c.renameContainer(1, "abc", "x")
        assertEquals(HttpMethod.Post, method)
        assertEquals("https://h/api/endpoints/1/docker/containers/abc/rename?name=x", url)
    }

    @Test
    fun renameMissingContainerIsNotFound() = runTest {
        val c = PortainerClient("https://h", "k", MockEngine { respond("""{"message":"No such container: abc"}""", HttpStatusCode.NotFound, json) })
        assertFailsWith<PortainerException.NotFound> { c.renameContainer(1, "abc", "x") }
    }

    @Test
    fun connectPostsContainerAndEndpointConfig() = runTest {
        var url: String? = null
        var body: String? = null
        val c = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                url = request.url.toString()
                body = bodyText(request.body)
                respond("", HttpStatusCode.OK)
            },
        )
        val config = JsonObject(mapOf("Aliases" to JsonArray(listOf(JsonPrimitive("web")))))
        c.connectNetwork(1, "backend", "new123", config)
        assertEquals("https://h/api/endpoints/1/docker/networks/backend/connect", url)
        val sent = Json.parseToJsonElement(body!!).jsonObject
        assertEquals("new123", sent["Container"]?.jsonPrimitive?.content)
        assertEquals(config, sent["EndpointConfig"])
    }

    @Test
    fun inspectReturnsRawObject() = runTest {
        var url: String? = null
        val c = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                url = request.url.toString()
                respond("""{"Id":"abc","Name":"/web","HostConfig":{"AutoRemove":true}}""", HttpStatusCode.OK, json)
            },
        )
        val obj = c.inspectContainer(1, "abc")
        assertEquals("https://h/api/endpoints/1/docker/containers/abc/json", url)
        assertEquals("/web", obj["Name"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), obj["HostConfig"]?.jsonObject?.get("AutoRemove"))
    }

    @Test
    fun stopWaitsForContainerStopTimeout() = runTest {
        val seen = mutableListOf<Pair<String, HttpTimeoutConfig?>>()
        val c = PortainerClient(
            "https://h",
            "k",
            MockEngine { request ->
                seen += request.url.toString() to request.getCapabilityOrNull(HttpTimeoutCapability)
                respond("", HttpStatusCode.NoContent)
            },
        )
        c.stopContainer(1, "abc")
        c.startContainer(1, "abc")
        val (stopUrl, stopTimeout) = seen[0]
        assertEquals("https://h/api/endpoints/1/docker/containers/abc/stop", stopUrl, "no t= parameter: Docker uses the container's own stop timeout")
        assertEquals(300_000L, stopTimeout?.requestTimeoutMillis)
        assertEquals(300_000L, stopTimeout?.socketTimeoutMillis)
        assertNull(seen[1].second?.requestTimeoutMillis?.takeIf { it == 300_000L })
    }
}
