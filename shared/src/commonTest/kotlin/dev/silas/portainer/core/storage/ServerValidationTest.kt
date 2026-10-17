package dev.silas.portainer.core.storage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServerValidationTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun okCountsDockerEnvironments() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/status" -> respond("""{"Version":"2.21.0"}""", HttpStatusCode.OK, json)
                "/api/endpoints" -> respond(
                    """[{"Id":1,"Name":"a","Type":1,"Status":1},{"Id":2,"Name":"b","Type":5,"Status":1}]""",
                    HttpStatusCode.OK,
                    json,
                )
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        assertEquals(
            ValidationResult.Ok("2.21.0", 1),
            validateServer("https://h", "k", engine),
        )
    }

    @Test
    fun unauthorizedIsInvalidKey() = runTest {
        val engine = MockEngine { respond("""{"message":"no"}""", HttpStatusCode.Unauthorized, json) }
        assertEquals(ValidationResult.InvalidKey, validateServer("https://h", "k", engine))
    }

    @Test
    fun ioFailureIsUnreachable() = runTest {
        val engine = MockEngine { throw IOException("boom") }
        assertIs<ValidationResult.Unreachable>(validateServer("https://h", "k", engine))
    }

    @Test
    fun otherApiErrorIsFailed() = runTest {
        val engine = MockEngine { respond("""{"message":"bad"}""", HttpStatusCode.InternalServerError, json) }
        assertIs<ValidationResult.Failed>(validateServer("https://h", "k", engine))
    }
}
