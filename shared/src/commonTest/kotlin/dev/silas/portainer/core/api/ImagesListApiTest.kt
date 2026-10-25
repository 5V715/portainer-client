package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.ImageSummary
import dev.silas.portainer.core.model.isProtected
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImagesListApiTest {
    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String, onRequest: (HttpRequestData) -> Unit = {}) =
        PortainerClient("https://h", "test-api-key", MockEngine { onRequest(it); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })

    @Test
    fun listParsesTaggedAndDanglingImages() = runTest {
        var req: HttpRequestData? = null
        val body = """[
          {"Id":"sha256:aaa","RepoTags":["nginx:1.27"],"Size":1000,"Created":1700000000,"Labels":{"a":"b"}},
          {"Id":"sha256:bbb","RepoTags":["<none>:<none>"],"Size":5,"Created":1600000000,"Labels":null},
          {"Id":"sha256:ccc","RepoTags":null,"Size":7,"Created":1500000000}
        ]"""
        val images = client(body = body) { req = it }.images(3)
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("/api/endpoints/3/docker/images/json", r.url.encodedPath)
        assertEquals(null, r.url.parameters["all"])
        assertEquals(3, images.size)
        assertEquals(ImageSummary("sha256:aaa", listOf("nginx:1.27"), 1000, 1700000000, mapOf("a" to "b")), images[0])
        assertFalse(images[0].dangling)
        assertTrue(images[1].dangling)
        assertEquals(emptyMap(), images[1].labels)
        assertEquals(emptyList(), images[2].repoTags)
        assertTrue(images[2].dangling)
    }

    @Test
    fun removeForceSendsDeleteWithForceParam() = runTest {
        var req: HttpRequestData? = null
        client(body = "[]") { req = it }.removeImage(1, "sha256:abc", force = true)
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Delete, r.method)
        assertEquals("/api/endpoints/1/docker/images/sha256:abc", r.url.encodedPath.replace("%3A", ":"))
        assertEquals("true", r.url.parameters["force"])
    }

    @Test
    fun removeDefaultsToNoForce() = runTest {
        var req: HttpRequestData? = null
        client(body = "[]") { req = it }.removeImage(1, "sha256:abc")
        assertEquals("false", req!!.url.parameters["force"])
    }

    @Test
    fun conflictMapsToApiWithDockerMessage() = runTest {
        val c = client(HttpStatusCode.Conflict, "{\"message\":\"conflict: unable to delete abc (must be forced) - image is being used by stopped container\"}")
        val e = assertFailsWith<PortainerException.Api> { c.removeImage(1, "sha256:abc") }
        assertEquals(409, e.status)
        assertEquals("conflict: unable to delete abc (must be forced) - image is being used by stopped container", e.message)
    }

    @Test
    fun portainerImageIsProtected() {
        assertTrue(ImageSummary(repoTags = listOf("portainer/portainer-ce:2.21.4")).isProtected())
        assertFalse(ImageSummary(repoTags = listOf("nginx:1")).isProtected())
        assertFalse(ImageSummary(repoTags = emptyList()).isProtected())
    }
}
