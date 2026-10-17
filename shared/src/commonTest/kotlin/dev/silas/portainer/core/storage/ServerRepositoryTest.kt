package dev.silas.portainer.core.storage

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRepositoryTest {
    private val config = ServerConfig("s1", "Home", "https://nas.example")

    @Test
    fun saveStoresConfigAndKey() = runTest {
        val repo = ServerRepository(MapSettings(), createSecretStore())
        repo.save(config, "key-1")
        assertEquals(listOf(config), repo.servers.value)
        assertEquals("key-1", repo.apiKey("s1"))
    }

    @Test
    fun keyIsNotInSettings() = runTest {
        val settings = MapSettings()
        val repo = ServerRepository(settings, createSecretStore())
        repo.save(config, "super-secret-key")
        for (k in settings.keys) {
            assertFalse(settings.getString(k, "").contains("super-secret-key"), "key leaked in $k")
        }
    }

    @Test
    fun nullKeyKeepsExistingKey() = runTest {
        val repo = ServerRepository(MapSettings(), createSecretStore())
        repo.save(config, "key-1")
        repo.save(config.copy(name = "Renamed"), null)
        assertEquals("Renamed", repo.servers.value.single().name)
        assertEquals("key-1", repo.apiKey("s1"))
    }

    @Test
    fun deleteRemovesConfigAndKey() = runTest {
        val repo = ServerRepository(MapSettings(), createSecretStore())
        repo.save(config, "key-1")
        repo.delete("s1")
        assertTrue(repo.servers.value.isEmpty())
        assertNull(repo.apiKey("s1"))
    }

    @Test
    fun lastEndpointPersistsAcrossInstances() = runTest {
        val settings = MapSettings()
        val secrets = createSecretStore()
        val repo = ServerRepository(settings, secrets)
        repo.save(config, "key-1")
        repo.setLastEndpoint("s1", 7)
        val reloaded = ServerRepository(settings, secrets)
        assertEquals(7, reloaded.servers.value.single().lastEndpointId)
    }
}
