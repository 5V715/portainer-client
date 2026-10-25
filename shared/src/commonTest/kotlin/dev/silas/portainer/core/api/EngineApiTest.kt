package dev.silas.portainer.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(swarmState: String, urls: MutableList<String> = mutableListOf()) = PortainerClient(
        "https://h",
        "k",
        MockEngine { request ->
            urls += request.url.toString()
            when (request.url.encodedPath.substringAfterLast("/docker/")) {
                "version" -> respond("""{"Version":"27.3.1","ApiVersion":"1.47","Os":"linux"}""", HttpStatusCode.OK, json)
                "info" -> respond("""{"ID":"x","Swarm":{"NodeID":"","LocalNodeState":"$swarmState"}}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )

    @Test
    fun inactiveSwarm() = runTest {
        val urls = mutableListOf<String>()
        val info = client("inactive", urls).engineInfo(3)
        assertEquals("1.47", info.apiVersion)
        assertFalse(info.swarmActive)
        assertTrue("https://h/api/endpoints/3/docker/version" in urls)
        assertTrue("https://h/api/endpoints/3/docker/info" in urls)
    }

    @Test
    fun activeSwarm() = runTest {
        assertTrue(client("active").engineInfo(3).swarmActive)
    }

    @Test
    fun missingSwarmSectionMeansInactive() = runTest {
        val c = PortainerClient("https://h", "k", MockEngine { request ->
            if (request.url.encodedPath.endsWith("/version")) respond("""{"ApiVersion":"1.41"}""", HttpStatusCode.OK, json)
            else respond("{}", HttpStatusCode.OK, json)
        })
        assertFalse(c.engineInfo(1).swarmActive)
    }
}
