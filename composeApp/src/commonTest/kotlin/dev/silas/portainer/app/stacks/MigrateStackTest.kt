package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.drain
import dev.silas.portainer.app.settle
import dev.silas.portainer.app.support.CONNECTION_DEPENDENCY_REMINDER
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Duplicate and migrate: the sheet's rules (view model) and the migrate session's confirmation (sessions). */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrateStackTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private val bodies = MutableStateFlow(emptyMap<String, JsonObject>())

    private val endpointsJson = """[
        {"Id":1,"Name":"local","Type":1,"Status":1},
        {"Id":2,"Name":"agent","Type":2,"Status":1},
        {"Id":3,"Name":"agent-down","Type":2,"Status":2},
        {"Id":4,"Name":"edge","Type":4,"Status":1},
        {"Id":5,"Name":"k8s","Type":6,"Status":1},
        {"Id":6,"Name":"azure","Type":3,"Status":1}
    ]"""

    private var stackJson = """{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":1,
        "Env":[{"name":"A","value":"1"}]}"""
    private var fileContent = "services:\n  web:\n    image: nginx\n"
    private var fileFails = false
    private var containersJson = "[]"
    /** Stacks listed per endpoint id. */
    private val stacksOn = mutableMapOf(
        1 to """[{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":1}]""",
        2 to """[{"Id":7,"Name":"taken","Type":2,"EndpointId":2,"Status":1}]""",
    )
    /** `Status` of the migrated stack on endpoint 2 once the migrate went through; each list reads the next one. */
    private var migratedStatuses = mutableListOf(1)
    @Volatile private var migrated = false
    /** The name the migrated stack has on endpoint 2. */
    private var migratedName = "shop"
    private var migrateFails = false
    private var createFails = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData = { req ->
        val path = req.url.encodedPath
        requestLog.update { it + "${req.method.value} $path${req.url.encodedQuery.takeIf { q -> q.isNotEmpty() }?.let { q -> "?$q" }.orEmpty()}" }
        (req.body as? TextContent)?.let { b ->
            bodies.update { it + ("${req.method.value} $path" to Json.parseToJsonElement(b.text).jsonObject) }
        }
        when {
            path == "/api/status" -> respond("""{"Version":"2.45.3"}""", HttpStatusCode.OK, json)
            path == "/api/endpoints" -> respond(endpointsJson, HttpStatusCode.OK, json)
            path == "/api/stacks" && req.method == HttpMethod.Get -> {
                val endpoint = Regex("\"EndpointID\":(\\d+)").find(req.url.parameters["filters"].orEmpty())?.groupValues?.get(1)?.toInt()
                if (endpoint == 2 && migrated) {
                    val status = if (migratedStatuses.size > 1) migratedStatuses.removeAt(0) else migratedStatuses.first()
                    respond("""[{"Id":5,"Name":"$migratedName","Type":2,"EndpointId":2,"Status":$status}]""", HttpStatusCode.OK, json)
                } else {
                    respond(stacksOn[endpoint] ?: "[]", HttpStatusCode.OK, json)
                }
            }
            path == "/api/stacks/5" && req.method == HttpMethod.Get ->
                if (migrated) respond("""{"message":"Unable to find a stack"}""", HttpStatusCode.NotFound, json) else respond(stackJson, HttpStatusCode.OK, json)
            path == "/api/stacks/5/file" ->
                if (fileFails) {
                    respond("""{"message":"Stack git settings have changed"}""", HttpStatusCode.Conflict, json)
                } else {
                    respond("""{"StackFileContent":"${fileContent.replace("\n", "\\n")}"}""", HttpStatusCode.OK, json)
                }
            path == "/api/stacks/5/migrate" ->
                if (migrateFails) {
                    respond("""{"message":"A stack with that name exists on the target secret-key"}""", HttpStatusCode.Conflict, json)
                } else {
                    migrated = true
                    respond("{}", HttpStatusCode.OK, json)
                }
            path.startsWith("/api/stacks/create/standalone/") ->
                if (createFails) {
                    respond("""{"message":"clone failed for hunter2"}""", HttpStatusCode.InternalServerError, json)
                } else {
                    respond("""{"Id":9,"Name":"x","Type":2,"EndpointId":2,"Status":1}""", HttpStatusCode.OK, json)
                }
            path == "/api/stacks/9" -> respond("""{"Id":9,"Name":"x","Type":2,"EndpointId":2,"Status":1}""", HttpStatusCode.OK, json)
            path.endsWith("/containers/json") -> respond(containersJson, HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private val vms = mutableListOf<StackDetailViewModel>()

    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        block()
        drain { vms.all { !it.state.value.loading && !it.state.value.starting } }
    }

    private suspend fun TestScope.loaded(stackId: Int? = 5, name: String = "shop"): StackDetailViewModel {
        val env = TestEnv(handler)
        env.repo.save(ServerConfig("s1", "A", "https://a.example.com"), "secret-key")
        val vm = StackDetailViewModel(env.container, "s1", 1, stackId, name).also { vms += it }
        settle { !vm.state.value.loading && (vm.state.value.stack != null || vm.state.value.error != null) }
        return vm
    }

    /** Opens the sheet and waits until its target list and the default target's names are loaded. */
    private suspend fun TestScope.opened(vm: StackDetailViewModel, mode: TransferMode): TransferForm {
        vm.openTransfer(mode)
        settle { vm.state.value.transfer?.let { !it.loadingTargets && it.existingNames != null } == true }
        return assertNotNull(vm.state.value.transfer)
    }

    private suspend fun TestScope.target(vm: StackDetailViewModel, id: Int) {
        vm.setTransferTarget(id)
        settle { vm.state.value.transfer?.let { it.targetId == id && it.existingNames != null } == true }
    }

    private val writes: List<String> get() = requests.filter { !it.startsWith("GET") }

    @Test
    fun targetListIsOnlineDockerStandaloneEndpointsAndMigrateLeavesOutTheSource() = vmTest {
        val vm = loaded()
        assertEquals(listOf(1, 2), opened(vm, TransferMode.Duplicate).targets.map { it.id })
        vm.dismissTransfer()
        assertNull(vm.state.value.transfer)
        // Offline, Edge, Kubernetes and Azure environments never appear; the source is no migrate target.
        assertEquals(listOf(2), opened(vm, TransferMode.Migrate).targets.map { it.id })
    }

    @Test
    fun migrateNeedsTheTypedNameAndShowsTheConnectionReminder() = vmTest {
        val vm = loaded()
        val form = opened(vm, TransferMode.Migrate)
        assertEquals(CONNECTION_DEPENDENCY_REMINDER, form.reminder)
        assertEquals(2, form.targetId)
        assertFalse(form.canSubmit)
        vm.setTransferTypedName("shop ")
        assertFalse(vm.state.value.transfer!!.canSubmit)
        vm.submitTransfer()
        settle { !vm.state.value.starting }
        assertTrue(writes.isEmpty(), writes.toString())
        vm.setTransferTypedName("shop")
        assertTrue(vm.state.value.transfer!!.canSubmit)
    }

    @Test
    fun migrateIsBlockedWhenTheStackIsProtected() = vmTest {
        fileContent = "services:\n  agent:\n    image: portainer/agent:2.45.3\n"
        val vm = loaded()
        vm.openTransfer(TransferMode.Migrate)
        settle { vm.state.value.error != null }
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
        assertNull(vm.state.value.transfer)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun migrateIsBlockedWhenProtectionIsUnknown() = vmTest {
        fileFails = true
        val vm = loaded()
        vm.openTransfer(TransferMode.Migrate)
        settle { vm.state.value.error != null }
        assertEquals(PROTECTION_UNKNOWN_MESSAGE, vm.state.value.error)
        assertNull(vm.state.value.transfer)
    }

    @Test
    fun externalStacksOfferNeitherAction() = vmTest {
        containersJson = """[{"Id":"c3","Names":["/other"],"Image":"redis","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"other"}}]"""
        val vm = loaded(stackId = null, name = "other")
        for (mode in TransferMode.entries) {
            vm.openTransfer(mode)
            settle { vm.state.value.error != null }
            assertEquals(EXTERNAL_STACK_MESSAGE, vm.state.value.error)
            vm.clearError()
            assertNull(vm.state.value.transfer)
        }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun migrateSendsTheRequestFromTheSourceAndEndsOnTheListWhenTheStackIsOnTheTarget() = vmTest {
        migratedName = "shop-2"
        val vm = loaded()
        opened(vm, TransferMode.Migrate)
        vm.setTransferName("shop-2")
        vm.setTransferTypedName("shop")
        vm.submitTransfer()
        settle { vm.state.value.deleted }
        assertEquals(listOf("POST /api/stacks/5/migrate?endpointId=1"), writes)
        val body = assertNotNull(bodies.value["POST /api/stacks/5/migrate"])
        assertEquals("2", body["EndpointID"]!!.jsonPrimitive.content)
        assertEquals("shop-2", body["Name"]!!.jsonPrimitive.content)
        // It is only done once the target lists the stack under its new name.
        assertTrue(requests.any { it.startsWith("GET /api/stacks?") }, requests.toString())
    }

    @Test
    fun migrateKeepsTheNameWhenItIsNotChanged() = vmTest {
        val vm = loaded()
        opened(vm, TransferMode.Migrate)
        vm.setTransferTypedName("shop")
        vm.submitTransfer()
        settle { vm.state.value.deleted }
        assertFalse("Name" in assertNotNull(bodies.value["POST /api/stacks/5/migrate"]))
    }

    @Test
    fun migrateRefusesANameTheTargetAlreadyUses() = vmTest {
        val vm = loaded()
        opened(vm, TransferMode.Migrate)
        vm.setTransferName("taken")
        assertEquals(STACK_NAME_TAKEN_MESSAGE, vm.state.value.transfer!!.nameError)
        assertFalse(vm.state.value.transfer!!.canSubmit)
        vm.setTransferName("Bad Name")
        assertEquals(STACK_NAME_RULE_MESSAGE, vm.state.value.transfer!!.nameError)
    }

    @Test
    fun aFailedMigrateKeepsTheSheetAndTheFormAndHidesTheApiKey() = vmTest {
        migrateFails = true
        migratedName = "shop-2"
        val vm = loaded()
        opened(vm, TransferMode.Migrate)
        vm.setTransferName("shop-2")
        vm.setTransferTypedName("shop")
        vm.submitTransfer()
        settle { vm.state.value.transfer?.error != null }
        val form = vm.state.value.transfer!!
        assertFalse(form.running)
        assertEquals("shop-2", form.name)
        assertEquals("shop", form.typedName)
        assertEquals(2, form.targetId)
        assertFalse(form.error!!.contains("secret-key"), form.error)
        assertNull(vm.state.value.failure)
        assertFalse(vm.state.value.deleted)
        // The user can try again.
        migrateFails = false
        vm.submitTransfer()
        settle { vm.state.value.deleted }
    }

    @Test
    fun duplicateOfAWebEditorStackCreatesFromItsFileAndEnvAndNeverWritesToTheSource() = vmTest {
        val vm = loaded()
        val form = opened(vm, TransferMode.Duplicate)
        assertEquals("shop-copy", form.name)
        assertEquals(1, form.targetId) // the same environment is allowed
        target(vm, 2)
        vm.setTransferName("shop2")
        vm.submitTransfer()
        settle { vm.state.value.notice != null }
        assertNull(vm.state.value.transfer)
        assertEquals(listOf("POST /api/stacks/create/standalone/string?endpointId=2"), writes)
        val body = assertNotNull(bodies.value["POST /api/stacks/create/standalone/string"])
        assertEquals("shop2", body["Name"]!!.jsonPrimitive.content)
        assertEquals(fileContent, body["StackFileContent"]!!.jsonPrimitive.content)
        assertEquals("""[{"name":"A","value":"1"}]""", body["Env"].toString())
        assertTrue(requests.none { it.contains("/api/stacks/5/") && !it.endsWith("/file") }, requests.toString())
    }

    @Test
    fun duplicateOnTheSameEnvironmentNeedsADifferentName() = vmTest {
        val vm = loaded()
        opened(vm, TransferMode.Duplicate)
        vm.setTransferName("shop")
        assertEquals(STACK_NAME_TAKEN_MESSAGE, vm.state.value.transfer!!.nameError)
        assertFalse(vm.state.value.transfer!!.canSubmit)
        vm.setTransferName("SHOP")
        assertFalse(vm.state.value.transfer!!.canSubmit)
        vm.setTransferName("shop-b")
        assertTrue(vm.state.value.transfer!!.canSubmit)
        // On another environment the same name is fine, unless it is used there.
        target(vm, 2)
        vm.setTransferName("shop")
        assertTrue(vm.state.value.transfer!!.canSubmit)
        vm.setTransferName("taken")
        assertEquals(STACK_NAME_TAKEN_MESSAGE, vm.state.value.transfer!!.nameError)
    }

    private val gitStack = """{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":1,
        "Env":[{"name":"A","value":"1"}],"AdditionalFiles":["override.yml"],
        "AutoUpdate":{"Interval":"5m","Webhook":"05de31a2-79fa-4644-9c12-faa67e5c49f0"},
        "GitConfig":{"URL":"https://git.example.com/shop.git","ReferenceName":"refs/heads/main","ConfigFilePath":"deploy/compose.yml",
        "Authentication":{"Username":"deploy"},"TLSSkipVerify":true}"""

    @Test
    fun duplicateOfAGitStackNeedsThePasswordAndSendsItOnlyToTheCreate() = vmTest {
        stackJson = "$gitStack}"
        val vm = loaded()
        val form = opened(vm, TransferMode.Duplicate)
        assertTrue(form.passwordNeeded)
        assertTrue(form.autoUpdateDropped)
        vm.setTransferName("shop-b")
        assertFalse(vm.state.value.transfer!!.canSubmit)
        vm.setTransferPassword("hunter2")
        assertTrue(vm.state.value.transfer!!.canSubmit)
        assertFalse(vm.state.value.transfer.toString().contains("hunter2"))
        vm.submitTransfer()
        settle { vm.state.value.notice != null }
        assertEquals(listOf("POST /api/stacks/create/standalone/repository?endpointId=1"), writes)
        val body = assertNotNull(bodies.value["POST /api/stacks/create/standalone/repository"])
        assertEquals("https://git.example.com/shop.git", body["RepositoryURL"]!!.jsonPrimitive.content)
        assertEquals("refs/heads/main", body["RepositoryReferenceName"]!!.jsonPrimitive.content)
        assertEquals("deploy/compose.yml", body["ComposeFile"]!!.jsonPrimitive.content)
        assertEquals("""["override.yml"]""", body["AdditionalFiles"].toString())
        assertEquals("deploy", body["RepositoryUsername"]!!.jsonPrimitive.content)
        assertEquals("hunter2", body["RepositoryPassword"]!!.jsonPrimitive.content)
        assertEquals("true", body["TLSSkipVerify"]!!.jsonPrimitive.content)
        assertEquals("null", body["AutoUpdate"].toString()) // webhooks are unique; polling is the Source's
        assertFalse("SourceID" in body)
    }

    @Test
    fun duplicateOfAGitOpsSourceStackPassesTheSourceAndNeedsNoPassword() = vmTest {
        stackJson = "$gitStack,\"GitSourceId\":7}"
        val vm = loaded()
        val form = opened(vm, TransferMode.Duplicate)
        assertFalse(form.passwordNeeded)
        assertTrue(form.usesGitSource)
        vm.setTransferName("shop-b")
        vm.submitTransfer()
        settle { vm.state.value.notice != null }
        val body = assertNotNull(bodies.value["POST /api/stacks/create/standalone/repository"])
        assertEquals("7", body["SourceID"]!!.jsonPrimitive.content)
        assertEquals("false", body["RepositoryAuthentication"]!!.jsonPrimitive.content)
    }

    @Test
    fun duplicateOfAGitStackWithoutCredentialsNeedsNoPasswordAndNoFile() = vmTest {
        stackJson = """{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":1,
            "GitConfig":{"URL":"https://git.example.com/pub.git","ReferenceName":"refs/heads/main","ConfigFilePath":"compose.yml"}}"""
        fileFails = true // the stack's own file can't be read; the repository is what is copied
        val vm = loaded()
        val form = opened(vm, TransferMode.Duplicate)
        assertFalse(form.passwordNeeded)
        vm.setTransferName("pub")
        vm.submitTransfer()
        settle { vm.state.value.notice != null }
        assertEquals(listOf("POST /api/stacks/create/standalone/repository?endpointId=1"), writes)
    }

    @Test
    fun aFailedDuplicateKeepsTheSheetDropsThePasswordAndHidesIt() = vmTest {
        stackJson = "$gitStack}"
        createFails = true
        val vm = loaded()
        opened(vm, TransferMode.Duplicate)
        vm.setTransferName("shop-b")
        vm.setTransferPassword("hunter2")
        vm.submitTransfer()
        settle { vm.state.value.transfer?.error != null }
        val form = vm.state.value.transfer!!
        assertFalse(form.running)
        assertEquals("shop-b", form.name)
        assertEquals("", form.password)
        assertFalse(form.error!!.contains("hunter2"), form.error)
        assertFalse(form.canSubmit) // type the password again
        assertNull(vm.state.value.notice)
        assertNull(vm.state.value.failure)
        assertTrue(writes.all { it.startsWith("POST /api/stacks/create/") }, writes.toString())
    }

    // ---- the migrate session ----

    private fun sessions() = StackSessions(
        scope,
        { PortainerClient("https://a.example.com", "secret-key", MockEngine(handler)) },
        pollIntervalMs = 5,
        pollTimeoutMs = 100,
    )

    private val key = StackKey("s1", 1, "shop")

    private suspend fun TestScope.finished(sessions: StackSessions): StackProgress {
        settle { sessions.progress(key).value?.running == false }
        return assertNotNull(sessions.progress(key).value)
    }

    @Test
    fun migrateIsDoneOnlyWhenTheStackIsOnTheTargetAndSettled() = runTest {
        migratedStatuses = mutableListOf(3, 3, 1)
        val sessions = sessions()
        assertTrue(sessions.start(key, 5, StackOp.Migrate(2, null)))
        assertNull(finished(sessions).error)
        assertEquals("POST /api/stacks/5/migrate?endpointId=1", requests.first { it.startsWith("POST") })
        // Read back until it stopped deploying: deploying, deploying, active.
        assertEquals(3, requests.count { it.startsWith("GET /api/stacks?") }, requests.toString())
    }

    @Test
    fun migrateThatEndsInErrorIsNotDone() = runTest {
        migratedStatuses = mutableListOf(4)
        val sessions = sessions()
        assertTrue(sessions.start(key, 5, StackOp.Migrate(2, null)))
        assertNotNull(finished(sessions).error)
    }

    @Test
    fun migrateWhoseStackNeverShowsUpOnTheTargetIsNotDone() = runTest {
        migratedName = "somewhere-else"
        val sessions = sessions()
        assertTrue(sessions.start(key, 5, StackOp.Migrate(2, null)))
        assertNotNull(finished(sessions).error)
    }

    @Test
    fun migrateWhoseRequestFailsIsNotDone() = runTest {
        migrateFails = true
        val sessions = sessions()
        assertTrue(sessions.start(key, 5, StackOp.Migrate(2, null)))
        val error = assertNotNull(finished(sessions).error)
        assertFalse(error.contains("secret-key"), error)
        assertEquals(listOf("POST /api/stacks/5/migrate?endpointId=1"), writes)
    }

    @Test
    fun migrateIsRefusedWhileTheTargetEndpointHasOtherWork() = runTest {
        val sessions = StackSessions(
            scope,
            { PortainerClient("https://a.example.com", "secret-key", MockEngine(handler)) },
            otherWork = { _, endpoint -> "busy".takeIf { endpoint == 2 } },
            pollIntervalMs = 5,
            pollTimeoutMs = 100,
        )
        assertFalse(sessions.start(key, 5, StackOp.Migrate(2, null)))
        assertTrue(writes.isEmpty())
    }
}
