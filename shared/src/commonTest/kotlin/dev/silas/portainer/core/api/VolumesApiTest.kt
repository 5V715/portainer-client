package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.Volume
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolumesApiTest {
    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String, onRequest: (HttpRequestData) -> Unit = {}) =
        PortainerClient("https://h", "test-api-key", MockEngine { onRequest(it); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })

    private val hex = "a".repeat(64)

    @Test
    fun listParsesVolumes() = runTest {
        var req: HttpRequestData? = null
        val body = """{"Volumes":[
          {"Name":"data","Driver":"local","Mountpoint":"/var/lib/docker/volumes/data/_data","Scope":"local","CreatedAt":"2024-05-01T10:00:00Z","Labels":{"a":"b"},"Options":{"type":"nfs"}},
          {"Name":"bare","Driver":"local","Mountpoint":"/m","Scope":"local","Labels":null,"Options":null}
        ],"Warnings":null}"""
        val volumes = client(body = body) { req = it }.volumes(3)
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("/api/endpoints/3/docker/volumes", r.url.encodedPath)
        assertEquals(
            Volume("data", "local", "/var/lib/docker/volumes/data/_data", "local", "2024-05-01T10:00:00Z", mapOf("a" to "b"), mapOf("type" to "nfs")),
            volumes[0],
        )
        assertNull(volumes[1].createdAt)
        assertEquals(emptyMap(), volumes[1].labels)
        assertEquals(emptyMap(), volumes[1].options)
    }

    @Test
    fun nullVolumesListIsEmpty() = runTest {
        assertEquals(emptyList(), client(body = """{"Volumes":null,"Warnings":null}""").volumes(1))
    }

    @Test
    fun anonymousDetectedByHexNameOrLabel() {
        assertTrue(Volume(name = hex).anonymous)
        assertTrue(Volume(name = "named", labels = mapOf("com.docker.volume.anonymous" to "")).anonymous)
        assertFalse(Volume(name = "named").anonymous)
        assertFalse(Volume(name = "A".repeat(64)).anonymous)
        assertFalse(Volume(name = "a".repeat(63)).anonymous)
        assertFalse(Volume(name = "g".repeat(64)).anonymous)
    }

    @Test
    fun inspectGetsVolumeByName() = runTest {
        var req: HttpRequestData? = null
        val v = client(body = """{"Name":"data","Driver":"local","Mountpoint":"/m","Scope":"local"}""") { req = it }.inspectVolume(2, "data")
        assertEquals("/api/endpoints/2/docker/volumes/data", req!!.url.encodedPath)
        assertEquals("data", v.name)
    }

    @Test
    fun createWithBlankNameOmitsNameAndDefaultsToLocal() = runTest {
        var req: HttpRequestData? = null
        val v = client(HttpStatusCode.Created, """{"Name":"$hex","Driver":"local","Mountpoint":"/m","Scope":"local"}""") { req = it }
            .createVolume(1, "  ")
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("/api/endpoints/1/docker/volumes/create", r.url.encodedPath)
        val sent = Json.parseToJsonElement((r.body as TextContent).text).jsonObject
        assertFalse("Name" in sent)
        assertEquals(JsonPrimitive("local"), sent["Driver"])
        assertEquals(hex, v.name)
        assertTrue(v.anonymous)
    }

    @Test
    fun createSendsNameOptionsAndLabels() = runTest {
        var req: HttpRequestData? = null
        client(HttpStatusCode.Created, """{"Name":"db","Driver":"local","Mountpoint":"/m","Scope":"local"}""") { req = it }
            .createVolume(1, "db", "local", mapOf("type" to "nfs"), mapOf("k" to "v"))
        val sent = Json.parseToJsonElement((req!!.body as TextContent).text).jsonObject
        assertEquals(JsonPrimitive("db"), sent["Name"])
        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("nfs"))), sent["DriverOpts"])
        assertEquals(JsonObject(mapOf("k" to JsonPrimitive("v"))), sent["Labels"])
    }

    @Test
    fun removeSendsDeleteWithoutForce() = runTest {
        var req: HttpRequestData? = null
        client(HttpStatusCode.NoContent, "") { req = it }.removeVolume(1, "data")
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Delete, r.method)
        assertEquals("/api/endpoints/1/docker/volumes/data", r.url.encodedPath)
        assertNull(r.url.parameters["force"])
    }

    @Test
    fun inUseConflictMapsToApiWithMessage() = runTest {
        val c = client(HttpStatusCode.Conflict, """{"message":"remove data: volume is in use - [abc]"}""")
        val e = assertFailsWith<PortainerException.Api> { c.removeVolume(1, "data") }
        assertEquals(409, e.status)
        assertEquals("remove data: volume is in use - [abc]", e.message)
    }
}
