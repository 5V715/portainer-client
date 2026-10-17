package dev.silas.portainer.core.api

import dev.silas.portainer.core.platform.httpEngine
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val REQUEST_TIMEOUT_MS = 15_000L

/** [baseUrl] must already be normalised (`https://host[:port][/sub-path]`, no trailing slash). */
class PortainerClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val engine: HttpClientEngine = httpEngine(),
) {
    internal val json = Json { ignoreUnknownKeys = true }

    internal val http: HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        expectSuccess = false
        // Never follow redirects: they would re-send X-API-Key to whatever host the 3xx names.
        followRedirects = false
    }

    /** Closes the client and the engine it was given (an explicitly passed engine is not closed by `HttpClient.close()`). */
    fun close() {
        http.close()
        engine.close()
    }

    /**
     * Runs a request against [path] (starting with `/`), maps failures to [PortainerException]
     * and returns the successful response. `http.request {}` buffers the full response body, so
     * this is NOT usable for streaming (logs, pulls): streamed calls must use `http.prepareRequest`
     * with [throwForStatus] and a per-request timeout override.
     */
    internal suspend fun execute(
        path: String,
        okStatuses: Set<Int> = emptySet(),
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
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
        throwForStatus(response, okStatuses)
        return response
    }

    /**
     * Prepares a streamed request against [path]: URL and API key applied, nothing executed yet.
     * Run it with `.execute { }` and call [throwForStatus]; map transport failures with [mapTransportFailure].
     */
    internal suspend fun prepareStream(path: String, block: HttpRequestBuilder.() -> Unit = {}): HttpStatement =
        http.prepareRequest {
            url(baseUrl + path)
            header(API_KEY_HEADER, apiKey)
            block()
        }

    /**
     * Maps a throwable from a streamed call to [PortainerException]; message never echoes the cause
     * because it can contain the request URL or headers.
     */
    internal fun mapTransportFailure(e: Throwable): Throwable = when (e) {
        is CancellationException, is PortainerException -> e
        else -> PortainerException.Unreachable("Could not reach Portainer")
    }

    /**
     * Maps a non-2xx [response] to a [PortainerException]; also usable on streamed responses.
     * Statuses in [okStatuses] (e.g. Docker's 304 for "already started") are accepted as success.
     */
    internal suspend fun throwForStatus(response: HttpResponse, okStatuses: Set<Int> = emptySet()) {
        if (response.status.isSuccess() || response.status.value in okStatuses) return
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
        if (text.isEmpty()) return null
        return redact(text)
    }

    /** Replaces the API key in server-provided [text] before it can reach an exception or the UI. */
    internal fun redact(text: String): String = if (apiKey.isBlank()) text else text.replace(apiKey, "***")

    /** GET [path] and decode the JSON body, mapping read/parse failures to [PortainerException]. */
    internal suspend inline fun <reified T> getJson(path: String): T {
        val response = execute(path)
        return decodeBody(response) { response.body<T>() }
    }

    internal suspend inline fun <T> decodeBody(response: HttpResponse, decode: () -> T): T = try {
        decode()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PortainerException) {
        throw e
    } catch (e: SerializationException) {
        throw PortainerException.Api(response.status.value, "Unexpected response from Portainer")
    } catch (e: IOException) {
        throw PortainerException.Unreachable("Could not reach Portainer")
    } catch (e: Throwable) {
        // Ktor wraps parse failures (NoTransformationFoundException, JsonConvertException) and timeouts.
        if (e is HttpRequestTimeoutException) {
            throw PortainerException.Unreachable("Could not reach Portainer")
        }
        throw PortainerException.Api(response.status.value, "Unexpected response from Portainer")
    }

    private companion object {
        const val API_KEY_HEADER = "X-API-Key"
    }
}

internal fun dockerPath(endpointId: Int, path: String): String = "/api/endpoints/$endpointId/docker/$path"
