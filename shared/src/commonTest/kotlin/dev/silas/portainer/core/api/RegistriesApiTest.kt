package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.RegistryInput
import dev.silas.portainer.core.model.RegistryType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistriesApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val created = """{"Id":7,"Name":"GH","Type":8,"URL":"ghcr.io","Authentication":true,"Username":"me"}"""

    private suspend fun body(r: HttpRequestData): JsonObject =
        Json.parseToJsonElement(r.body.toByteArray().decodeToString()).jsonObject

    @Test
    fun listParsesAndToleratesMissingCredentials() = runTest {
        val c = PortainerClient("https://h", "k", MockEngine {
            respond(
                """[{"Id":1,"Name":"Hub","Type":6,"URL":"docker.io","Authentication":false,"Extra":1},
                    {"Id":2,"Name":"X","Type":99,"URL":"x.io","Authentication":true,"Username":"u"}]""",
                HttpStatusCode.OK, jsonHeaders,
            )
        })
        val list = c.registries()
        assertEquals(RegistryType.DockerHub, list[0].type)
        assertNull(list[0].username)
        assertEquals(RegistryType.Unknown, list[1].type)
        assertEquals("u", list[1].username)
    }

    @Test
    fun createPostsFieldsAndReturnsRegistry() = runTest {
        var req: HttpRequestData? = null
        var sent: JsonObject? = null
        val c = PortainerClient("https://h", "k", MockEngine { req = it; sent = body(it); respond(created, HttpStatusCode.OK, jsonHeaders) })
        val r = c.createRegistry(RegistryInput("GH", RegistryType.GitHub, "ghcr.io", true, "me", "tok"))
        assertEquals(7, r.id)
        assertEquals(HttpMethod.Post, req!!.method)
        assertEquals("/api/registries", req!!.url.encodedPath)
        val s = sent!!
        assertEquals("GH", s["Name"]!!.jsonPrimitive.content)
        assertEquals(8, s["Type"]!!.jsonPrimitive.int)
        assertEquals("ghcr.io", s["URL"]!!.jsonPrimitive.content)
        assertTrue(s["Authentication"]!!.jsonPrimitive.boolean)
        assertEquals("me", s["Username"]!!.jsonPrimitive.content)
        assertEquals("tok", s["Password"]!!.jsonPrimitive.content)
    }

    @Test
    fun updateWithNullPasswordOmitsPassword() = runTest {
        var req: HttpRequestData? = null
        var sent: JsonObject? = null
        val c = PortainerClient("https://h", "k", MockEngine { req = it; sent = body(it); respond("{}", HttpStatusCode.OK, jsonHeaders) })
        c.updateRegistry(7, RegistryInput("GH", RegistryType.GitHub, "ghcr.io", true, "me", null))
        assertEquals(HttpMethod.Put, req!!.method)
        assertEquals("/api/registries/7", req!!.url.encodedPath)
        assertFalse(sent!!.containsKey("Password"))
        assertEquals("me", sent!!["Username"]!!.jsonPrimitive.content)
    }

    @Test
    fun ecrRegionAndBaseUrlAreSent() = runTest {
        var sent: JsonObject? = null
        val c = PortainerClient("https://h", "k", MockEngine { sent = body(it); respond(created, HttpStatusCode.OK, jsonHeaders) })
        c.createRegistry(RegistryInput("E", RegistryType.Ecr, "x.dkr.ecr.eu-west-1.amazonaws.com", true, "k", "s", baseUrl = "b", ecrRegion = "eu-west-1"))
        assertEquals("eu-west-1", sent!!["Ecr"]!!.jsonObject["Region"]!!.jsonPrimitive.content)
        assertEquals("b", sent!!["BaseURL"]!!.jsonPrimitive.content)
    }

    @Test
    fun deleteUsesDelete() = runTest {
        var req: HttpRequestData? = null
        val c = PortainerClient("https://h", "k", MockEngine { req = it; respond("", HttpStatusCode.NoContent) })
        c.deleteRegistry(7)
        assertEquals(HttpMethod.Delete, req!!.method)
        assertEquals("/api/registries/7", req!!.url.encodedPath)
    }

    @Test
    fun inputToStringRedactsPassword() {
        val s = RegistryInput("GH", RegistryType.GitHub, "ghcr.io", true, "me", "hunter2").toString()
        assertFalse(s.contains("hunter2"))
    }
}
