package dev.silas.portainer.app.detail

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    private val queries = mutableListOf<Map<String, String>>()
    private var bodyFor: (Int) -> String = { "" }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun ts(n: Int) = "2026-10-17T10:${(n / 60).toString().padStart(2, '0')}:${(n % 60).toString().padStart(2, '0')}.000000000Z"

    private fun text(range: IntRange, prefix: String = "line") =
        range.joinToString("") { "${ts(it)} $prefix $it\n" }

    private suspend fun env(): TestEnv {
        val env = TestEnv { req ->
            queries += req.url.parameters.entries().associate { it.key to it.value.first() }
            respond(bodyFor(queries.size), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    private suspend fun vm(env: TestEnv) = LogsViewModel(env.container, "s1", 1, "abc", tty = true, streaming = true)

    @Test
    fun appendsLinesAndCapsAt5000() = runTest {
        bodyFor = { "" + (0 until 5100).joinToString("") { "2026-10-17T10:00:00.000000000Z l$it\n" } }
        val vm = vm(env())
        settle { vm.state.value.lines.size == 5000 && !vm.state.value.following }
        assertEquals("l100", vm.state.value.lines.first().text)
        assertEquals("l5099", vm.state.value.lines.last().text)
        assertEquals("200", queries.first()["tail"])
    }

    @Test
    fun changingTailCancelsPreviousAndRestarts() = runTest {
        bodyFor = { n -> text(0..1, "r$n") }
        val vm = vm(env())
        settle { vm.state.value.lines.size == 2 && !vm.state.value.following }
        vm.setTail(1000)
        settle { queries.size == 2 && vm.state.value.lines.map { it.text } == listOf("r2 0", "r2 1") && !vm.state.value.following }
        assertEquals("1000", queries[1]["tail"])
        assertEquals(1000, vm.state.value.tail)
        vm.setTail(null)
        settle { queries.size == 3 && !vm.state.value.following }
        assertEquals("all", queries[2]["tail"])
        assertNull(vm.state.value.tail)
    }

    @Test
    fun queryFiltersOnlyWhenMatchesOnly() = runTest {
        bodyFor = { "${ts(0)} hello world\n${ts(1)} goodbye\n${ts(2)} HELLO again\n" }
        val vm = vm(env())
        settle { vm.state.value.lines.size == 3 }
        vm.setQuery("hello")
        assertEquals(3, vm.state.value.visible.size)
        vm.setMatchesOnly(true)
        assertEquals(listOf("hello world", "HELLO again"), vm.state.value.visible.map { it.text })
        vm.setQuery("")
        assertEquals(3, vm.state.value.visible.size)
    }

    @Test
    fun pauseStopsAndResumeContinuesAfterLastTimestampWithoutDuplicates() = runTest {
        bodyFor = { n -> if (n == 1) text(0..2) else text(2..4) }
        val vm = vm(env())
        settle { vm.state.value.lines.size == 3 && !vm.state.value.following }
        vm.pause()
        vm.resume()
        settle { queries.size == 2 && vm.state.value.lines.size == 5 && !vm.state.value.following }
        assertEquals(listOf(0, 1, 2, 3, 4).map { "line $it" }, vm.state.value.lines.map { it.text })
        assertEquals("all", queries[1]["tail"])
        // 2026-10-17T10:00:02Z
        assertEquals("1792231202", queries[1]["since"])
    }

    @Test
    fun resumeWhileRunningDoesNotRestart() = runTest {
        bodyFor = { text(0..0) }
        val vm = vm(env())
        settle { vm.state.value.lines.size == 1 }
        vm.resume()
        settle { true }
        assertEquals(1, queries.size)
    }

    @Test
    fun failureSetsError() = runTest {
        val env = TestEnv { respond("""{"message":"nope"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json")) }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        val vm = vm(env)
        settle { vm.state.value.error != null }
        assertEquals("nope", vm.state.value.error)
        assertTrue(!vm.state.value.following)
    }

    @Test
    fun toggleTimestamps() = runTest {
        val vm = vm(env())
        assertTrue(!vm.state.value.showTimestamps)
        vm.toggleTimestamps()
        assertTrue(vm.state.value.showTimestamps)
    }

    // --- live collector: each request stays open until its gate is completed ---

    private val gates = mutableListOf<CompletableDeferred<String>>()
    private var outstanding = 0

    private suspend fun gatedEnv(): TestEnv {
        val env = TestEnv { req ->
            queries += req.url.parameters.entries().associate { it.key to it.value.first() }
            val gate = CompletableDeferred<String>()
            gates += gate
            outstanding++
            try {
                respond(gate.await(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            } finally {
                outstanding--
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    @Test
    fun changingTailTwiceLeavesOnlyTheLastCollectorAndIgnoresStaleOutput() = runTest {
        val vm = vm(gatedEnv())
        settle { gates.size == 1 }
        vm.setTail(1000)
        settle { gates.size == 2 && outstanding == 1 }
        vm.setTail(100)
        settle { gates.size == 3 && outstanding == 1 }
        assertEquals(listOf("200", "1000", "100"), queries.map { it["tail"] })
        gates[0].complete(text(0..0, "stale"))
        gates[1].complete(text(0..0, "stale"))
        gates[2].complete(text(0..1, "new"))
        settle { vm.state.value.lines.size == 2 && !vm.state.value.following }
        assertEquals(listOf("new 0", "new 1"), vm.state.value.lines.map { it.text })
    }

    @Test
    fun pauseMidStreamStopsTheRequestAndIgnoresLateOutput() = runTest {
        val vm = vm(gatedEnv())
        settle { gates.size == 1 && vm.state.value.following }
        vm.pause()
        settle { outstanding == 0 }
        assertTrue(!vm.state.value.following)
        gates[0].complete(text(0..2, "late"))
        advanceUntilIdle()
        assertTrue(vm.state.value.lines.isEmpty())
        assertTrue(!vm.state.value.following)
    }

    @Test
    fun clearingTheViewModelCancelsTheCollector() = runTest {
        val vm = vm(gatedEnv())
        settle { gates.size == 1 && outstanding == 1 }
        vm.onCleared()
        settle { outstanding == 0 }
    }
}
