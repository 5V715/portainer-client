package dev.silas.portainer.core.storage

private const val PREFIX = "portainer.secret."

private fun lsGet(key: String): String? = js("localStorage.getItem(key)")
private fun lsSet(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
private fun lsRemove(key: String): Unit = js("localStorage.removeItem(key)")

/** NOT secure: browser localStorage is readable by any script on this origin. */
private class LocalStorageSecretStore : SecretStore {
    override suspend fun get(key: String): String? = lsGet(PREFIX + key)
    override suspend fun put(key: String, value: String) = lsSet(PREFIX + key, value)
    override suspend fun remove(key: String) = lsRemove(PREFIX + key)
}

actual fun createSecretStore(): SecretStore = LocalStorageSecretStore()
