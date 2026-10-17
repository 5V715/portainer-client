package dev.silas.portainer.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizeBaseUrlTest {
    private val cases = listOf(
        " portainer.example.com " to "https://portainer.example.com",
        "https://nas.example:9443/" to "https://nas.example:9443",
        "https://nas.example/api" to "https://nas.example",
        "https://nas.example/api/" to "https://nas.example",
        "https://p.example" to "https://p.example",
        "api" to "https://api",
    )

    @Test
    fun normalises() {
        for ((input, expected) in cases) {
            assertEquals(expected, normalizeBaseUrl(input).getOrThrow(), "input: '$input'")
        }
    }

    @Test
    fun rejectsHttp() {
        val result = normalizeBaseUrl("http://nas.example")
        assertTrue(result.isFailure)
        assertEquals("Portainer must be reached over HTTPS", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsBlank() {
        assertTrue(normalizeBaseUrl("   ").isFailure)
    }
}
