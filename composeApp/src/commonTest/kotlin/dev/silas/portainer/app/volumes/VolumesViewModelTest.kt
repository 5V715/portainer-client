package dev.silas.portainer.app.volumes

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VolumesViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()
    private var createBody = ""
    private var listCalls = 0

    private val anon = "a".repeat(64)
    private val volumesJson = """{"Volumes":[
        {"Name":"data","Driver":"local","Mountpoint":"/v/data","Scope":"local","CreatedAt":"2026-10-01T10:00:00Z","Labels":{"com.docker.compose.project":"shop"}},
        {"Name":"free","Driver":"local","Mountpoint":"/v/free","Scope":"local","CreatedAt":"2026-10-01T10:00:00Z"},
        {"Name":"portainer_data","Driver":"local","Mountpoint":"/v/p","Scope":"local"},
        {"Name":"$anon","Driver":"local","Mountpoint":"/v/a","Scope":"local","Labels":{"com.docker.volume.anonymous":""}}
    ]}"""
    // "data" is used by a stopped container.
    private val containersJson = """[
        {"Id":"c1","Names":["/web"],"Image":"nginx","State":"exited","Status":"Exited (0)",
         "Mounts":[{"Type":"volume","Name":"data","Destination":"/d"}]}
    ]"""

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun env(): TestEnv {
        val env = TestEnv { req ->
            val path = req.url.encodedPath
            requests += "${req.method.value} $path"
            when {
                req.method == HttpMethod.Get && path.endsWith("/volumes") -> {
                    listCalls++
                    respond(volumesJson, HttpStatusCode.OK, json)
                }
                req.method == HttpMethod.Get && path.endsWith("/containers/json") -> respond(containersJson, HttpStatusCode.OK, json)
                req.method == HttpMethod.Post && path.endsWith("/volumes/create") -> {
                    createBody = when (val b = req.body) {
                        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
                        is TextContent -> b.text
                        else -> ""
                    }
                    val name = if (createBody.contains("\"Name\"")) "named" else "generated123"
                    respond("""{"Name":"$name","Driver":"local"}""", HttpStatusCode.Created, json)
                }
                req.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "k")
        return env
    }

    private fun deletes() = requests.filter { it.startsWith("DELETE") }

    private suspend fun kotlinx.coroutines.test.TestScope.loaded(): VolumesViewModel {
        val vm = VolumesViewModel(env().container, "s1", 1)
        settle { vm.state.value.items.isNotEmpty() && !vm.state.value.loading }
        return vm
    }

    @Test
    fun removableOnlyWhenUnusedAndNotProtected() = runTest {
        val vm = loaded()
        val byName = vm.state.value.items.associateBy { it.volume.name }
        assertFalse(byName.getValue("data").removable) // used by a stopped container
        assertFalse(byName.getValue("portainer_data").removable)
        assertTrue(byName.getValue("free").removable)
        assertTrue(byName.getValue("free").unused)
    }

    @Test
    fun usedAndProtectedRemovalsAreRefusedWithoutApiCall() = runTest {
        val vm = loaded()
        vm.remove("data")
        settle { vm.state.value.error != null }
        assertEquals("This volume is in use or belongs to Portainer and can't be removed.", vm.state.value.error)
        vm.clearError()
        vm.remove("portainer_data")
        settle { vm.state.value.error != null }
        assertEquals("This volume is in use or belongs to Portainer and can't be removed.", vm.state.value.error)
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun unusedVolumeIsRemovedAndListReloaded() = runTest {
        val vm = loaded()
        vm.remove("free")
        settle { deletes().size == 1 && listCalls == 2 && !vm.state.value.loading }
        assertEquals(listOf("DELETE /api/endpoints/1/docker/volumes/free"), deletes())
    }

    @Test
    fun blankNameSendsNoNameAndShowsGeneratedName() = runTest {
        val vm = loaded()
        vm.create("  ", "local", emptyList(), emptyList())
        settle { vm.state.value.create.status is CreateStatus.Done }
        assertFalse(createBody.contains("\"Name\""), createBody)
        assertEquals(CreateStatus.Done("Created generated123"), vm.state.value.create.status)
        settle { listCalls == 2 }
    }

    @Test
    fun createSendsOptionsAndLabelsSkippingBlankKeys() = runTest {
        val vm = loaded()
        vm.create("named", "local", listOf("type" to "nfs", " " to "x"), listOf("env" to "prod"))
        settle { vm.state.value.create.status is CreateStatus.Done }
        assertTrue(createBody.contains("\"Name\":\"named\""), createBody)
        assertTrue(createBody.contains("\"DriverOpts\":{\"type\":\"nfs\"}"), createBody)
        assertTrue(createBody.contains("\"Labels\":{\"env\":\"prod\"}"), createBody)
    }

    @Test
    fun searchFiltersByName() = runTest {
        val vm = loaded()
        vm.setQuery("FRE")
        assertEquals(listOf("free"), vm.state.value.visible.map { it.volume.name })
        vm.setQuery("zzz")
        assertTrue(vm.state.value.visible.isEmpty())
    }

    @Test
    fun anonymousShortIdAndStack() = runTest {
        val vm = loaded()
        val items = vm.state.value.items.associateBy { it.volume.name }
        assertEquals(anon.take(12), items.getValue(anon).title)
        assertTrue(items.getValue(anon).volume.anonymous)
        assertEquals("shop", items.getValue("data").stack)
    }
}

class VolumeMountParsingTest {
    @Test
    fun readsPathAndReadOnlyForTheNamedVolumeOnly() {
        val inspect = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"Mounts":[{"Type":"volume","Name":"data","Destination":"/d","RW":false},
                {"Type":"volume","Name":"other","Destination":"/o","RW":true},
                {"Type":"volume","Name":"data","Destination":"/e","RW":true}]}""",
        ) as kotlinx.serialization.json.JsonObject
        assertEquals(listOf(VolumeMount("/d", true), VolumeMount("/e", false)), parseMounts(inspect, "data"))
    }
}

class Rfc3339Test {
    @Test
    fun parsesZuluFractionAndOffset() {
        assertEquals(1_700_000_000L, dev.silas.portainer.app.images.parseRfc3339("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000L, dev.silas.portainer.app.images.parseRfc3339("2023-11-14T22:13:20.123456Z"))
        assertEquals(1_700_000_000L, dev.silas.portainer.app.images.parseRfc3339("2023-11-15T00:13:20+02:00"))
        assertEquals(null, dev.silas.portainer.app.images.parseRfc3339("garbage"))
        assertEquals("unknown", dev.silas.portainer.app.images.formatCreatedRfc3339(null, 1_700_000_000L))
    }
}
