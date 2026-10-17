package dev.silas.portainer.core.storage

/** In-memory store, used by tests only. */
private class MemorySecretStore : SecretStore {
    private val map = HashMap<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
}

actual fun createSecretStore(): SecretStore = MemorySecretStore()
