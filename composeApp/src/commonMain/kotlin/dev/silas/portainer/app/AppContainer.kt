package dev.silas.portainer.app

import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.platform.httpEngine
import dev.silas.portainer.core.storage.ServerConfig
import dev.silas.portainer.core.storage.ServerRepository
import dev.silas.portainer.app.recreate.RecreateSessions
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** App-wide dependencies. Caches one [PortainerClient] per server until the server is saved or deleted. */
class AppContainer(
    val servers: ServerRepository,
    /** Outlives every screen: long-running work (container recreate) must not be tied to a ViewModel. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    val engineFactory: () -> HttpClientEngine = ::httpEngine,
) {
    /** Running and finished recreates, so screens can come and go without affecting the operation. */
    val recreateSessions = RecreateSessions(appScope)

    private val lock = Mutex()
    private val clients = mutableMapOf<String, PortainerClient>()

    suspend fun client(serverId: String): PortainerClient = lock.withLock {
        clients[serverId]?.let { return it }
        val config = servers.servers.value.firstOrNull { it.id == serverId }
            ?: throw IllegalStateException("Unknown server")
        val key = servers.apiKey(serverId) ?: throw IllegalStateException("No API key stored for ${config.name}")
        PortainerClient(config.baseUrl, key, engineFactory()).also { clients[serverId] = it }
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
