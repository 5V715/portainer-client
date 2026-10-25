package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.IpamSubnet
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.model.NetworkMember
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworksApiTest {
    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String, onRequest: (HttpRequestData) -> Unit = {}) =
        PortainerClient("https://h", "test-api-key", MockEngine { onRequest(it); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })

    @Test
    fun listParsesNetworksIncludingIpam() = runTest {
        var req: HttpRequestData? = null
        val body = """[
          {"Id":"n1","Name":"appnet","Driver":"bridge","Scope":"local","Internal":true,"Attachable":true,
           "IPAM":{"Driver":"default","Config":[{"Subnet":"172.18.0.0/16","Gateway":"172.18.0.1","IPRange":"172.18.5.0/24"}]},
           "Containers":{},"Labels":{"a":"b"}},
          {"Id":"n2","Name":"host","Driver":"host","Scope":"local","Internal":false,"Attachable":false,
           "IPAM":{"Driver":"default","Config":null},"Containers":null,"Labels":null},
          {"Id":"n3","Name":"min","Driver":"bridge","Scope":"local"}
        ]"""
        val nets = client(body = body) { req = it }.networks(3)
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("/api/endpoints/3/docker/networks", r.url.encodedPath)
        assertEquals(
            Network("n1", "appnet", "bridge", "local", true, true, listOf(IpamSubnet("172.18.0.0/16", "172.18.0.1", "172.18.5.0/24")), mapOf("a" to "b"), emptyMap()),
            nets[0],
        )
        assertEquals(emptyList(), nets[1].subnets)
        assertEquals(emptyMap(), nets[1].labels)
        assertEquals(emptyMap(), nets[1].containers)
        assertEquals(emptyList(), nets[2].subnets)
    }

    @Test
    fun predefinedOnlyForBridgeHostNone() {
        fun n(name: String) = Network("i", name, "bridge", "local", false, false, emptyList(), emptyMap(), emptyMap())
        assertTrue(n("bridge").predefined)
        assertTrue(n("host").predefined)
        assertTrue(n("none").predefined)
        assertFalse(n("appnet").predefined)
        assertFalse(n("bridge2").predefined)
    }

    @Test
    fun inspectParsesContainers() = runTest {
        var req: HttpRequestData? = null
        val body = """{"Id":"n1","Name":"appnet","Driver":"bridge","Scope":"local",
          "IPAM":{"Config":[{"Subnet":"10.0.0.0/24"}]},
          "Containers":{"c1":{"Name":"web","EndpointID":"e","MacAddress":"m","IPv4Address":"172.18.0.2/16","IPv6Address":""},
                        "c2":{"Name":"db","IPv4Address":"","IPv6Address":"fd00::2/64"}}}"""
        val n = client(body = body) { req = it }.inspectNetwork(2, "n1")
        assertEquals("/api/endpoints/2/docker/networks/n1", req!!.url.encodedPath)
        assertEquals(IpamSubnet("10.0.0.0/24", null, null), n.subnets.single())
        assertEquals(NetworkMember("web", "172.18.0.2/16", null), n.containers["c1"])
        assertEquals(NetworkMember("db", null, "fd00::2/64"), n.containers["c2"])
    }

    @Test
    fun createWithoutSubnetOmitsIpam() = runTest {
        var req: HttpRequestData? = null
        val id = client(HttpStatusCode.Created, """{"Id":"newid","Warning":""}""") { req = it }.createNetwork(1, NetworkInput("web"))
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("/api/endpoints/1/docker/networks/create", r.url.encodedPath)
        assertEquals("newid", id)
        val sent = Json.parseToJsonElement((r.body as TextContent).text).jsonObject
        assertEquals(JsonPrimitive("web"), sent["Name"])
        assertEquals(JsonPrimitive("bridge"), sent["Driver"])
        assertEquals(JsonPrimitive(false), sent["Internal"])
        assertEquals(JsonPrimitive(false), sent["Attachable"])
        assertEquals(JsonPrimitive(true), sent["CheckDuplicate"])
        assertEquals(JsonObject(emptyMap()), sent["Options"])
        assertEquals(JsonObject(emptyMap()), sent["Labels"])
        assertFalse("IPAM" in sent)
    }

    @Test
    fun createWithSubnetSendsIpam() = runTest {
        var req: HttpRequestData? = null
        client(HttpStatusCode.Created, """{"Id":"x"}""") { req = it }.createNetwork(
            1,
            NetworkInput("web", "overlay", "10.1.0.0/24", "10.1.0.1", null, internal = true, attachable = true, options = mapOf("o" to "1"), labels = mapOf("k" to "v")),
        )
        val sent = Json.parseToJsonElement((req!!.body as TextContent).text).jsonObject
        assertEquals(JsonPrimitive("overlay"), sent["Driver"])
        assertEquals(JsonPrimitive(true), sent["Internal"])
        assertEquals(JsonPrimitive(true), sent["Attachable"])
        assertEquals(JsonObject(mapOf("o" to JsonPrimitive("1"))), sent["Options"])
        assertEquals(JsonObject(mapOf("k" to JsonPrimitive("v"))), sent["Labels"])
        val ipam = sent["IPAM"]!!.jsonObject
        assertEquals(JsonPrimitive("default"), ipam["Driver"])
        val cfg = ipam["Config"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("10.1.0.0/24"), cfg["Subnet"])
        assertEquals(JsonPrimitive("10.1.0.1"), cfg["Gateway"])
        assertFalse("IPRange" in cfg)
    }

    @Test
    fun createWithoutIdFails() = runTest {
        assertFailsWith<PortainerException.Api> { client(HttpStatusCode.Created, """{}""").createNetwork(1, NetworkInput("web")) }
    }

    @Test
    fun removeSendsDelete() = runTest {
        var req: HttpRequestData? = null
        client(HttpStatusCode.NoContent, "") { req = it }.removeNetwork(1, "n1")
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Delete, r.method)
        assertEquals("/api/endpoints/1/docker/networks/n1", r.url.encodedPath)
        assertNull(r.url.parameters["force"])
    }
}
