package dev.silas.portainer.core.api

import dev.silas.portainer.core.platform.httpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val REQUEST_TIMEOUT_MS = 15_000L

/** [baseUrl] must already be normalised (`https://host[:port]`, no trailing slash). */
class PortainerClient(
    private val baseUrl: String,
    private val apiKey: String,
    engine: HttpClientEngine = httpEngine(),
) {
    internal val json = Json { ignoreUnknownKeys = true }

    internal val http: HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        expectSuccess = false
    }

    fun close() = http.close()

    /**
     * Runs a request against [path] (starting with `/`), maps failures to [PortainerException]
     * and returns the successful response. The body is not consumed for 2xx responses.
     */
    internal suspend fun execute(path: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val response = try {
            http.request {
                url(baseUrl + path)
                header(API_KEY_HEADER, apiKey)
                block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PortainerException) {
            throw e
        } catch (e: Throwable) {
            // Deliberately not echoing e.message: it can contain the request URL or headers.
            throw PortainerException.Unreachable("Could not reach Portainer")
        }
        throwForStatus(response)
        return response
    }

    /** Maps a non-2xx [response] to a [PortainerException]; also usable on streamed responses. */
    internal suspend fun throwForStatus(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val status = response.status.value
        when (status) {
            401, 403 -> throw PortainerException.Unauthorized()
            404 -> throw PortainerException.NotFound(
                errorText(response)?.let { "Not found: $it" } ?: "Not found",
            )
        }
        throw PortainerException.Api(status, errorText(response) ?: response.status.description.ifBlank { "HTTP $status" })
    }

    private suspend fun errorText(response: HttpResponse): String? {
        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return null
        }
        val obj = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val details = (obj["details"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val text = listOfNotNull(message, details).filter { it.isNotBlank() }.joinToString(": ")
        return text.takeIf { it.isNotEmpty() }?.replace(apiKey, "***")
    }

    private companion object {
        const val API_KEY_HEADER = "X-API-Key"
    }
}

internal fun dockerPath(endpointId: Int, path: String): String = "/api/endpoints/$endpointId/docker/$path"
