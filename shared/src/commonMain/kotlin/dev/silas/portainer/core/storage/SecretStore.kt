package dev.silas.portainer.core.storage

/** Platform-protected storage for API keys. */
interface SecretStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

expect fun createSecretStore(): SecretStore
