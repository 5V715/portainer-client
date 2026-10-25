package dev.silas.portainer.core.resources

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageResolutionTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val idOnly = "sha256:" + "d".repeat(64)

    private fun container(id: String, image: String) =
        ContainerSummary(id, id, image, ContainerState.Exited, "Exited", null, null, emptyMap())

    @Test
    fun resolvesOnlyIdOnlyImagesAndSkipsFailures() = runTest {
        // Appended from the engine's threads: an atomic snapshot list.
        val paths = MutableStateFlow(emptyList<String>())
        val client = PortainerClient("https://h", "k", MockEngine { req ->
            val path = req.url.encodedPath
            paths.update { it + path }
            when {
                path.endsWith("/containers/old/json") -> respond("""{"Config":{"Image":"portainer/portainer-ce:2.19"}}""", HttpStatusCode.OK, json)
                path.endsWith("/containers/again/json") -> respond("""{"Config":{"Image":"$idOnly"}}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.InternalServerError, json)
            }
        })
        val cs = listOf(container("old", idOnly), container("again", idOnly), container("broken", idOnly), container("web", "nginx"))
        val resolved = client.resolveIdOnlyImages(1, cs)
        assertEquals(mapOf("old" to "portainer/portainer-ce:2.19"), resolved)
        assertEquals(3, paths.value.size) // "web" shows a name and is never inspected
    }
}
