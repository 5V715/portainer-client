package dev.silas.portainer.app.support

import dev.silas.portainer.core.api.PortainerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

const val REQUEST_FAILED_MESSAGE = "Request failed"
const val INTERRUPTED_MESSAGE = "The connection was interrupted"

/**
 * The text a failure may show. Only [PortainerException] (whose messages are redacted of the API key) and the
 * app's own [IllegalStateException]s keep their message; anything else becomes [fallback], so nothing
 * unexpected or sensitive reaches the UI.
 */
fun safeMessage(e: Throwable, fallback: String = REQUEST_FAILED_MESSAGE): String = when (e) {
    is PortainerException, is IllegalStateException -> e.message?.takeIf { it.isNotBlank() } ?: fallback
    else -> fallback
}

/**
 * The one error mapping for view models: rethrows [e] when it is a [CancellationException] and the calling
 * coroutine itself was cancelled; a cancellation from elsewhere (a client closed by invalidation) becomes
 * [INTERRUPTED_MESSAGE]; everything else goes through [safeMessage].
 */
suspend fun failureMessage(e: Throwable, fallback: String = REQUEST_FAILED_MESSAGE): String {
    if (e is CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        return INTERRUPTED_MESSAGE
    }
    return safeMessage(e, fallback)
}
