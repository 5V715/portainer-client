package dev.silas.portainer.core.api

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemApiTest {
    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String, onRequest: (HttpRequestData) -> Unit = {}) =
        PortainerClient("https://h", "test-api-key", MockEngine { onRequest(it); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })

    @Test
    fun dfParsesVolumeSizesAndTreatsMinusOneAsUnknown() = runTest {
        var req: HttpRequestData? = null
        val body = """{"LayersSize":5,"Images":[
          {"Id":"sha256:a","Size":1000,"SharedSize":300,"Containers":0},
          {"Id":"sha256:b","Size":50,"SharedSize":-1}
        ],"Containers":[],"Volumes":[
          {"Name":"data","UsageData":{"Size":2048,"RefCount":0}},
          {"Name":"unknown","UsageData":{"Size":-1,"RefCount":-1}},
          {"Name":"nousage"},
          {"Name":"nullusage","UsageData":null}
        ],"BuildCache":null}"""
        val df = client(body = body) { req = it }.systemDf(3)
        val r = assertNotNull(req)
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("/api/endpoints/3/docker/system/df", r.url.encodedPath)
        assertEquals(mapOf<String, Long?>("data" to 2048L, "unknown" to null, "nousage" to null, "nullusage" to null), df.volumeSizes)
    }

    @Test
    fun dfToleratesNullListsAndMissingFields() = runTest {
        val df = client(body = """{"Images":null,"Volumes":null}""").systemDf(1)
        assertTrue(df.volumeSizes.isEmpty())
        assertNull(client(body = """{"Volumes":[{"Name":"v"}]}""").systemDf(1).volumeSizes["v"])
    }

    @Test
    fun dfFailureSurfacesAsAnException() = runTest {
        assertFailsWith<PortainerException.Api> {
            client(HttpStatusCode.InternalServerError, """{"message":"boom"}""").systemDf(1)
        }
    }
}
