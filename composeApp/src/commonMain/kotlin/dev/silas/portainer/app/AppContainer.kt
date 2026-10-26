package dev.silas.portainer.app

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.platform.httpEngine
import dev.silas.portainer.core.storage.ServerConfig
import dev.silas.portainer.core.storage.ServerRepository
import dev.silas.portainer.app.cleanup.CleanupSessions
import dev.silas.portainer.app.editor.DeploySessions
import dev.silas.portainer.core.cleanup.CleanupKind
import dev.silas.portainer.app.recreate.RecreateSessions
import dev.silas.portainer.app.cleanup.RECREATE_IN_PROGRESS_MESSAGE
import dev.silas.portainer.app.editor.DEPLOY_IN_PROGRESS_MESSAGE
import dev.silas.portainer.app.recreate.CLEANUP_IN_PROGRESS_MESSAGE
import dev.silas.portainer.app.stacks.STACK_OPERATION_IN_PROGRESS_MESSAGE
import dev.silas.portainer.app.stacks.StackSessions
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * App-wide dependencies. Caches one [PortainerClient] per server until the server is saved or deleted;
 * recreates use a [dedicatedClient] instead, so invalidation never cuts one off.
 */
class AppContainer(
    val servers: ServerRepository,
    /** Outlives every screen: long-running work (container recreate) must not be tied to a ViewModel. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    val engineFactory: () -> HttpClientEngine = ::httpEngine,
) {
    /** Running and finished recreates, so screens can come and go without affecting the operation. */
    val recreateSessions: RecreateSessions = RecreateSessions(
        appScope,
        nowEpochSeconds,
        cleanupRunning = { serverId, endpointId -> cleanupSessions.isRunning(serverId, endpointId, CleanupKind.Containers) },
        stackRunning = { serverId, endpointId -> stackSessions.isRunning(serverId, endpointId) },
    )

    /** Running and finished cleanups, so switching tabs or leaving a list never stops or loses one. */
    val cleanupSessions: CleanupSessions = CleanupSessions(appScope, { dedicatedClient(it) }) { serverId, endpointId, kind ->
        when {
            kind == CleanupKind.Containers && recreateSessions.isRunning(serverId, endpointId) -> RECREATE_IN_PROGRESS_MESSAGE
            stackSessions.isRunning(serverId, endpointId) -> STACK_OPERATION_IN_PROGRESS_MESSAGE
            else -> null
        }
    }

    /** Running and finished copies / new containers, so leaving the editor never stops one. */
    val deploySessions: DeploySessions = DeploySessions(appScope) { serverId, endpointId ->
        STACK_OPERATION_IN_PROGRESS_MESSAGE.takeIf { stackSessions.isRunning(serverId, endpointId) }
    }

    /**
     * Running and finished stack start/stop/delete, so leaving the stack never stops or loses one. Refused while a
     * recreate, any cleanup or an editor deploy runs on the endpoint: each touches containers the stack may own.
     */
    val stackSessions: StackSessions = StackSessions(appScope, { dedicatedClient(it) }, otherWork = { serverId, endpointId ->
        when {
            recreateSessions.isRunning(serverId, endpointId) -> RECREATE_IN_PROGRESS_MESSAGE
            cleanupSessions.isRunningAny(serverId, endpointId) -> CLEANUP_IN_PROGRESS_MESSAGE
            deploySessions.isRunning(serverId, endpointId) -> DEPLOY_IN_PROGRESS_MESSAGE
            else -> null
        }
    })

    private val lock = Mutex()
    private val clients = mutableMapOf<String, PortainerClient>()

    suspend fun client(serverId: String): PortainerClient = lock.withLock {
        clients[serverId]?.let { return it }
        newClient(serverId).also { clients[serverId] = it }
    }

    /**
     * A client of its own for long-running work (a recreate) that saving or deleting the server must not
     * interrupt: it is never cached or invalidated, and the caller closes it.
     */
    suspend fun dedicatedClient(serverId: String): PortainerClient = newClient(serverId)

    private suspend fun newClient(serverId: String): PortainerClient {
        val config = servers.servers.value.firstOrNull { it.id == serverId }
            ?: throw IllegalStateException("Unknown server")
        val key = servers.apiKey(serverId) ?: throw IllegalStateException("No API key stored for ${config.name}")
        return PortainerClient(config.baseUrl, key, engineFactory())
    }

    suspend fun saveServer(config: ServerConfig, apiKey: String?) {
        servers.save(config, apiKey)
        invalidate(config.id)
    }

    suspend fun deleteServer(id: String) {
        servers.delete(id)
        invalidate(id)
    }

    private suspend fun invalidate(serverId: String) = lock.withLock {
        clients.remove(serverId)?.close()
    }
}
