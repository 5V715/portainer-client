package dev.silas.portainer.app

import com.russhwolf.settings.MapSettings
import dev.silas.portainer.core.storage.SecretStore
import dev.silas.portainer.core.storage.ServerRepository
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.advanceUntilIdle
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData

class FakeSecretStore : SecretStore {
    val map = mutableMapOf<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
}

class TestEnv(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val secrets = FakeSecretStore()
    val repo = ServerRepository(MapSettings(), secrets)
    val container = AppContainer(repo) { MockEngine(handler) }
}

/** Drives the test Main dispatcher while real engine work completes, until [done] holds. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
suspend fun kotlinx.coroutines.test.TestScope.settle(done: () -> Boolean) {
    repeat(500) {
        advanceUntilIdle()
        if (done()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(10) }
    }
    throw AssertionError("condition not reached")
}
