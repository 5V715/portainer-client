package dev.silas.portainer.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertTrue

class PortainerClientCloseTest {
    @Test
    fun closeAlsoClosesTheEngine() {
        val engine = MockEngine { respondOk() }
        val job = engine.coroutineContext[Job]!!
        val client = PortainerClient("https://p.example.com", "k", engine)
        assertTrue(job.isActive)
        client.close()
        assertTrue(job.isCompleted || job.isCancelled, "engine should be closed")
    }
}
