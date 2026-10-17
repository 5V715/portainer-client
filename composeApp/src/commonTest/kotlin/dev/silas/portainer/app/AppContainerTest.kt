package dev.silas.portainer.app

import com.russhwolf.settings.MapSettings
import dev.silas.portainer.core.storage.ServerConfig
import dev.silas.portainer.core.storage.ServerRepository
import dev.silas.portainer.core.api.endpoints
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertEquals

class AppContainerTest {
    private val cfg = ServerConfig("s1", "A", "https://a.example.com")

    private fun setup(): Triple<AppContainer, MutableList<MockEngine>, ServerRepository> {
        val engines = mutableListOf<MockEngine>()
        val repo = ServerRepository(MapSettings(), FakeSecretStore())
        val container = AppContainer(repo) { MockEngine { respond("[]") }.also(engines::add) }
        return Triple(container, engines, repo)
    }

    @Test
    fun clientIsCachedThenClosedAndRebuiltAfterSave() = runTest {
        val (container, engines, repo) = setup()
        repo.save(cfg, "k")
        val first = container.client("s1")
        assertSame(first, container.client("s1"))
        assertEquals(1, engines.size)
        container.saveServer(cfg.copy(name = "B"), null)
        assertFails { first.endpoints() }
        assertNotSame(first, container.client("s1"))
        assertEquals(2, engines.size)
    }

    @Test
    fun clientIsClosedAfterDelete() = runTest {
        val (container, engines, repo) = setup()
        repo.save(cfg, "k")
        val first = container.client("s1")
        container.deleteServer("s1")
        assertFails { first.endpoints() }
        assertFailsWith<IllegalStateException> { container.client("s1") }
    }
}
