package dev.silas.portainer.app.support

import dev.silas.portainer.core.api.PortainerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ErrorsTest {
    @Test
    fun portainerAndAppMessagesAreKept() = runTest {
        assertEquals("Not found: x", failureMessage(PortainerException.NotFound("Not found: x")))
        assertEquals("Unknown server", failureMessage(IllegalStateException("Unknown server")))
    }

    @Test
    fun unexpectedExceptionsNeverShowTheirText() = runTest {
        assertEquals("Request failed", failureMessage(RuntimeException("secret-key-123 in a URL")))
        assertEquals("Pull failed", failureMessage(IllegalArgumentException("secret"), fallback = "Pull failed"))
    }

    @Test
    fun foreignCancellationIsAnInterruption() = runTest {
        assertEquals(INTERRUPTED_MESSAGE, failureMessage(CancellationException("client closed")))
    }

    @Test
    fun ownCancellationIsRethrown() = runTest {
        val seen = CompletableDeferred<Throwable>()
        coroutineScope {
            val job = async {
                cancel()
                try {
                    failureMessage(CancellationException("mine"))
                } catch (e: CancellationException) {
                    seen.complete(e)
                    throw e
                }
            }
            assertFailsWith<CancellationException> { job.await() }
        }
        assertEquals("mine", seen.await().message)
    }
}
