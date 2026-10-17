package dev.silas.portainer.core.storage

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.PortainerException
import dev.silas.portainer.core.api.endpoints
import dev.silas.portainer.core.api.status
import dev.silas.portainer.core.platform.httpEngine
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException

/** Turns what a user typed into `https://host[:port][/sub-path]`, or fails for plain `http://`. */
fun normalizeBaseUrl(input: String): Result<String> {
    var url = input.trim()
    if (url.isEmpty()) return Result.failure(IllegalArgumentException("Enter the Portainer address"))
    if (url.startsWith("http://", ignoreCase = true)) {
        return Result.failure(IllegalArgumentException("Portainer must be reached over HTTPS"))
    }
    if (!url.startsWith("https://", ignoreCase = true)) url = "https://$url"
    // A pasted browser URL (`https://host:9443/#!/2/docker/containers`) carries a fragment or query: drop them.
    // Any other path is kept, for Portainer served under a sub-path behind a reverse proxy.
    val hostStart = "https://".length
    val queryOrFragment = url.indexOfAny(charArrayOf('?', '#'), hostStart)
    if (queryOrFragment >= 0) url = url.substring(0, queryOrFragment)
    url = "https://" + url.substring(hostStart).trimEnd('/')
    val slash = url.indexOf('/', hostStart)
    if (slash >= 0 && url.endsWith("/api", ignoreCase = true)) url = url.dropLast(4).trimEnd('/')
    if (url.length <= "https://".length) {
        return Result.failure(IllegalArgumentException("Enter the Portainer address"))
    }
    return Result.success(url)
}

sealed interface ValidationResult {
    data class Ok(val version: String, val dockerEnvironments: Int) : ValidationResult
    data object InvalidKey : ValidationResult
    data class Unreachable(val message: String) : ValidationResult
    data class Failed(val message: String) : ValidationResult
}

suspend fun validateServer(
    baseUrl: String,
    apiKey: String,
    engine: HttpClientEngine = httpEngine(),
): ValidationResult {
    val client = PortainerClient(baseUrl, apiKey, engine)
    return try {
        val version = client.status().version
        val docker = client.endpoints().count { it.type.supported }
        ValidationResult.Ok(version, docker)
    } catch (e: CancellationException) {
        throw e
    } catch (_: PortainerException.Unauthorized) {
        ValidationResult.InvalidKey
    } catch (e: PortainerException.Unreachable) {
        ValidationResult.Unreachable(e.message ?: "Could not reach Portainer")
    } catch (e: PortainerException) {
        ValidationResult.Failed(e.message ?: "Portainer returned an error")
    } finally {
        client.close()
    }
}
