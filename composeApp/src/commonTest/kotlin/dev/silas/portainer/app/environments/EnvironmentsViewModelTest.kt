package dev.silas.portainer.app.environments

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentsViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv {
            respond(
                """[{"Id":1,"Name":"local","Type":1,"Status":1},{"Id":2,"Name":"edge","Type":4,"Status":1}]""",
                HttpStatusCode.OK,
                json,
            )
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    @Test
    fun type4IsDisabledAndType1Enabled() = runTest {
        val vm = EnvironmentsViewModel(env().container, "s1")
        settle { vm.state !is EnvironmentsState.Loading }
        val items = assertIs<EnvironmentsState.Loaded>(vm.state).items
        assertTrue(items.first { it.endpoint.id == 1 }.enabled)
        assertFalse(items.first { it.endpoint.id == 2 }.enabled)
    }

    @Test
    fun selectingSupportedRemembersEndpointAndNavigates() = runTest {
        val env = env()
        val vm = EnvironmentsViewModel(env.container, "s1")
        settle { vm.state !is EnvironmentsState.Loading }
        val items = assertIs<EnvironmentsState.Loaded>(vm.state).items
        var navigated: Int? = null
        vm.select(items[1]) { navigated = it }
        advanceUntilIdle()
        assertEquals(null, navigated)
        vm.select(items[0]) { navigated = it }
        advanceUntilIdle()
        assertEquals(1, navigated)
        assertEquals(1, env.repo.servers.value.single().lastEndpointId)
    }
}
