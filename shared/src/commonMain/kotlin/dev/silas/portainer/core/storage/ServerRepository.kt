package dev.silas.portainer.core.storage

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val SERVERS_KEY = "servers"

/** Saved servers (non-secret, in [Settings]) plus their API keys (in [SecretStore]). */
class ServerRepository(
    private val settings: Settings,
    private val secrets: SecretStore,
) {
    private val serializer = ListSerializer(ServerConfig.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val state = MutableStateFlow(load())

    val servers: StateFlow<List<ServerConfig>> = state.asStateFlow()

    private fun load(): List<ServerConfig> {
        val raw = settings.getStringOrNull(SERVERS_KEY) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(list: List<ServerConfig>) {
        settings.putString(SERVERS_KEY, json.encodeToString(serializer, list))
        state.value = list
    }

    /** Adds or replaces [config]; a null [apiKey] keeps the key already stored. */
    suspend fun save(config: ServerConfig, apiKey: String?) = lock.withLock {
        if (apiKey != null) secrets.put(config.id, apiKey)
        val current = state.value
        persist(
            if (current.any { it.id == config.id }) {
                current.map { if (it.id == config.id) config else it }
            } else {
                current + config
            },
        )
    }

    suspend fun delete(id: String) = lock.withLock {
        persist(state.value.filterNot { it.id == id })
        secrets.remove(id)
    }

    suspend fun apiKey(id: String): String? = secrets.get(id)

    suspend fun setLastEndpoint(id: String, endpointId: Int) = lock.withLock {
        persist(state.value.map { if (it.id == id) it.copy(lastEndpointId = endpointId) else it })
    }
}
