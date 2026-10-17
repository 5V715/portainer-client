package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.ImageRef
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImagesApiTest {
    private fun denial(docker: String) =
        "Image pull failed: $docker. If the image is in a private registry, check its credentials in Registries."

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String, onRequest: (HttpRequestData) -> Unit = {}) =
        PortainerClient("https://h", "test-api-key", MockEngine { onRequest(it); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })

    @Test
    fun streamErrorWithHttp200Throws() = runTest {
        val c = client(body = "{\"status\":\"Pulling\"}\n{\"error\":\"manifest unknown\"}\n")
        val e = assertFailsWith<PortainerException.Api> { c.pullImage(1, ImageRef.parse("nginx:nope"), null) }
        assertTrue(e.message.orEmpty().contains("manifest unknown"))
        assertEquals(200, e.status)
    }

    @Test
    fun errorDetailMessageThrows() = runTest {
        val c = client(body = "{\"errorDetail\":{\"message\":\"boom\"}}\n")
        val e = assertFailsWith<PortainerException.Api> { c.pullImage(1, ImageRef.parse("nginx"), null) }
        assertEquals("boom", e.message)
    }

    @Test
    fun deniedStreamErrorUsesDenialMessage() = runTest {
        val c = client(body = "{\"error\":\"Head https://r/v2/x: denied: requested access to the resource is denied\"}\n")
        val e = assertFailsWith<PortainerException.Api> { c.pullImage(1, ImageRef.parse("r/x"), 3) }
        assertEquals(denial("Head https://r/v2/x: denied: requested access to the resource is denied"), e.message)
    }

    @Test
    fun unauthorizedStreamErrorUsesDenialMessage() = runTest {
        val c = client(body = "{\"error\":\"unauthorized: authentication required\"}")
        val e = assertFailsWith<PortainerException.Api> { c.pullImage(1, ImageRef.parse("r/x"), 3) }
        assertEquals(denial("unauthorized: authentication required"), e.message)
    }

    @Test
    fun http401WithDeniedBodyUsesDenialMessage() = runTest {
        val c = client(HttpStatusCode.Unauthorized, "{\"message\":\"pull access denied for x\"}")
        val e = assertFailsWith<PortainerException.Api> { c.pullImage(1, ImageRef.parse("r/x"), 3) }
        assertEquals(denial("pull access denied for x"), e.message)
    }

    @Test
    fun bare401StaysUnauthorized() = runTest {
        val c = client(HttpStatusCode.Unauthorized, "")
        assertFailsWith<PortainerException.Unauthorized> { c.pullImage(1, ImageRef.parse("nginx"), null) }
    }

    @Test
    fun successfulStreamCompletes() = runTest {
        client(body = "{\"status\":\"Pulling\"}\n{\"status\":\"Download complete\"}\n").pullImage(1, ImageRef.parse("nginx"), null)
    }

    @Test
    fun buildsRequestWithRegistryHeader() = runTest {
        var req: HttpRequestData? = null
        client(body = "{}") { req = it }.pullImage(2, ImageRef.parse("ghcr.io/me/app:2"), 4)
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("/api/endpoints/2/docker/images/create", r.url.encodedPath)
        assertEquals("ghcr.io/me/app", r.url.parameters["fromImage"])
        assertEquals("2", r.url.parameters["tag"])
        assertEquals("""{"registryId":4}""", Base64.decode(assertNotNull(r.headers["X-Registry-Auth"])).decodeToString())
    }

    @Test
    fun noRegistryMeansNoHeaderAndDefaultsToLatestOrDigest() = runTest {
        var req: HttpRequestData? = null
        val c = client(body = "{}") { req = it }
        c.pullImage(1, ImageRef.parse("nginx"), null)
        assertNull(req!!.headers["X-Registry-Auth"])
        assertEquals("latest", req!!.url.parameters["tag"])
        c.pullImage(1, ImageRef.parse("me/app@sha256:abc"), null)
        assertEquals("sha256:abc", req!!.url.parameters["tag"])
    }

    @Test
    fun inspectNotFoundIsNull() = runTest {
        assertNull(client(HttpStatusCode.NotFound, "{\"message\":\"No such image\"}").inspectImage(1, "abc"))
    }

    @Test
    fun inspectReturnsObject() = runTest {
        var req: HttpRequestData? = null
        val o = client(body = "{\"Id\":\"sha256:abc\"}") { req = it }.inspectImage(1, "abc")
        assertNotNull(o)
        assertEquals("/api/endpoints/1/docker/images/abc/json", req!!.url.encodedPath)
    }

    @Test
    fun rbacForbiddenStaysUnauthorized() = runTest {
        val c = client(HttpStatusCode.Forbidden, "{\"message\":\"Access denied to resource\"}")
        assertFailsWith<PortainerException.Unauthorized> { c.pullImage(1, ImageRef.parse("nginx"), null) }
    }

    @Test
    fun registryDenialOnNotFoundUsesDenialMessage() = runTest {
        val c = client(HttpStatusCode.NotFound, "{\"message\":\"pull access denied for x, repository does not exist or may require 'docker login'\"}")
        val e = assertFailsWith<PortainerException.Api> { c.pullImage(1, ImageRef.parse("x"), null) }
        assertEquals(denial("pull access denied for x, repository does not exist or may require 'docker login'"), e.message)
        assertEquals(404, e.status)
    }

    @Test
    fun pullFailureMessagesRedactTheApiKey() = runTest {
        val key = "ptr_secretkey123"
        fun keyed(status: HttpStatusCode, body: String) =
            PortainerClient("https://h", key, MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })
        val streamed = assertFailsWith<PortainerException.Api> {
            keyed(HttpStatusCode.OK, "{\"error\":\"unauthorized: token $key rejected\"}").pullImage(1, ImageRef.parse("r/x"), 3)
        }
        assertEquals(denial("unauthorized: token *** rejected"), streamed.message)
        val other = assertFailsWith<PortainerException.Api> {
            keyed(HttpStatusCode.OK, "{\"error\":\"manifest for $key unknown\"}").pullImage(1, ImageRef.parse("r/x"), 3)
        }
        assertEquals("manifest for *** unknown", other.message)
        val status = assertFailsWith<PortainerException.Api> {
            keyed(HttpStatusCode.InternalServerError, "{\"message\":\"boom $key\"}").pullImage(1, ImageRef.parse("r/x"), 3)
        }
        assertEquals("boom ***", status.message)
    }

    @Test
    fun tagAndDigestPullsByDigest() = runTest {
        var req: HttpRequestData? = null
        client(body = "{}") { req = it }.pullImage(1, ImageRef.parse("me/app:2@sha256:abc"), null)
        assertEquals("me/app", req!!.url.parameters["fromImage"])
        assertEquals("sha256:abc", req!!.url.parameters["tag"])
    }
}
