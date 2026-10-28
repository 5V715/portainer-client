package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.drain
import dev.silas.portainer.app.settle
import dev.silas.portainer.app.support.FILE_NOT_TEXT_MESSAGE
import dev.silas.portainer.app.support.FILE_TOO_LARGE_MESSAGE
import dev.silas.portainer.app.support.MAX_PICKED_FILE_BYTES
import dev.silas.portainer.app.support.PickedFile
import dev.silas.portainer.app.support.decodePickedFile
import dev.silas.portainer.core.api.TemplateVariable
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.serializer
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CreateStackViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private val createBodies = MutableStateFlow(emptyList<JsonObject>())

    @Volatile private var version = "2.45.3"
    @Volatile private var statusFails = false
    /** `Status` codes `GET /api/stacks/12` answers in turn (the last one repeats). */
    private val statuses = MutableStateFlow(listOf(3, 1))
    @Volatile private var statusMessage = ""
    @Volatile private var createStatus = HttpStatusCode.OK
    @Volatile private var createdAnyway = false
    @Volatile private var createGate: CompletableDeferred<Unit>? = null
    /** Holds a stop of stack 1 (another stack operation on the endpoint). */
    private val stopGate = CompletableDeferred<Unit>()
    private val key = "secret-api-key"
    private var testEnv: TestEnv? = null
    private val models = mutableListOf<CreateStackViewModel>()

    private val templateFile = "services:\n  web:\n    image: nginx:{{ TAG }}\n    ports:\n      - \"{{PORT}}:80\"\n    # {{ UNSET }} {{ OTHER }}\n"

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() {
        createGate?.complete(Unit)
        stopGate.complete(Unit)
        Dispatchers.resetMain()
    }

    private suspend fun env(): TestEnv {
        val e = TestEnv { req ->
            val path = req.url.encodedPath
            requestLog.update { it + "${req.method.value} $path" }
            when {
                path == "/api/status" ->
                    if (statusFails) respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json)
                    else respond("""{"Version":"$version"}""", HttpStatusCode.OK, json)
                path == "/api/stacks" -> {
                    // After a create that "failed" on the wire, Portainer may still have made the stack.
                    val made = if (createdAnyway && createBodies.value.isNotEmpty()) """,{"Id":12,"Name":"web","Type":2,"EndpointId":1,"Status":4}""" else ""
                    respond("""[{"Id":1,"Name":"shop","Type":2,"EndpointId":1,"Status":1}$made]""", HttpStatusCode.OK, json)
                }
                path.endsWith("/containers/json") -> respond(
                    """[{"Id":"c1","Names":["/legacy-db-1"],"Image":"postgres","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"legacy"}}]""",
                    HttpStatusCode.OK,
                    json,
                )
                path.startsWith("/api/stacks/create/standalone/") -> {
                    createBodies.update { it + Json.parseToJsonElement((req.body as TextContent).text).jsonObject }
                    createGate?.await()
                    if (createStatus != HttpStatusCode.OK) {
                        respond("""{"message":"compose up failed","details":"bad $key s3cret-token"}""", createStatus, json)
                    } else {
                        respond("""{"Id":12,"Name":"web","Type":2,"EndpointId":1,"Status":3}""", HttpStatusCode.OK, json)
                    }
                }
                path == "/api/stacks/12" -> {
                    var code = 0
                    statuses.update { list -> code = list.first(); if (list.size > 1) list.drop(1) else list }
                    val deployment = if (statusMessage.isEmpty()) "" else ""","DeploymentStatus":[{"Status":4,"Message":"$statusMessage"}]"""
                    respond("""{"Id":12,"Name":"web","Type":2,"EndpointId":1,"Status":$code$deployment}""", HttpStatusCode.OK, json)
                }
                path == "/api/stacks/1/stop" -> {
                    stopGate.await()
                    respond("{}", HttpStatusCode.OK, json)
                }
                path == "/api/custom_templates" -> respond(
                    """[{"Id":1,"Title":"Web","Description":"nginx","Type":2,"EdgeTemplate":false,
                        "Variables":[{"name":"TAG","label":"Tag","defaultValue":"1.27"},{"name":"PORT","label":"Port","defaultValue":""},
                                     {"name":"UNSET","label":"Unset","defaultValue":""}]},
                        {"Id":2,"Title":"Swarm","Description":"","Type":1},
                        {"Id":3,"Title":"Git","Description":"","Type":2,"GitConfig":{"URL":"https://git.example/t.git"}}]""",
                    HttpStatusCode.OK,
                    json,
                )
                path == "/api/custom_templates/3/git_fetch" && req.method == HttpMethod.Put ->
                    respond("""{"FileContent":${Json.encodeToString(String.serializer(), file)}}""", HttpStatusCode.OK, json)
                path == "/api/custom_templates/1/file" ->
                    respond("""{"FileContent":${Json.encodeToString(String.serializer(), templateFile)}}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        e.repo.save(ServerConfig("s1", "A", "https://a.example.com"), key)
        testEnv = e
        return e
    }

    private val container: AppContainer get() = testEnv!!.container

    private fun createTest(block: suspend TestScope.() -> Unit) = runTest {
        block()
        drain { models.all { m -> m.state.value.let { !it.loadingNames && !it.validating && !it.checking && !it.running && !it.loadingTemplates } } }
    }

    private suspend fun TestScope.model(c: AppContainer? = null): CreateStackViewModel {
        val vm = CreateStackViewModel(c ?: env().container, "s1", 1, newWebhookId = { "05de31a2-79fa-4644-9c12-faa67e5c49f0" }).also { models += it }
        settle { !vm.state.value.loadingNames }
        return vm
    }

    private suspend fun TestScope.typed(vm: CreateStackViewModel, name: String, text: String) {
        vm.setName(name)
        vm.setText(text)
        settle { !vm.state.value.validating }
    }

    private val file = "services:\n  web:\n    image: nginx\n"

    @Test
    fun nameRuleFollowsPortainer() {
        assertNull(validStackName("web-1_a"))
        assertEquals(STACK_NAME_RULE_MESSAGE, validStackName("Web"))
        assertEquals(STACK_NAME_RULE_MESSAGE, validStackName("my app"))
        assertEquals(STACK_NAME_RULE_MESSAGE, validStackName("café"))
        assertEquals(STACK_NAME_REQUIRED_MESSAGE, validStackName(""))
    }

    @Test
    fun nameAlreadyUsedOnTheEndpointIsRejectedIncludingExternalStacks() = createTest {
        val vm = model()
        assertEquals(setOf("shop", "legacy"), vm.state.value.existingNames)
        for (taken in listOf("shop", "legacy")) {
            typed(vm, taken, file)
            assertEquals(STACK_NAME_TAKEN_MESSAGE, vm.state.value.nameError)
            assertFalse(vm.state.value.canCreate)
        }
        vm.setName("Shop")
        assertEquals(STACK_NAME_RULE_MESSAGE, vm.state.value.nameError)
        vm.setName("web")
        assertNull(vm.state.value.nameError)
        assertTrue(vm.state.value.canCreate)
    }

    @Test
    fun webEditorCreateSendsFileAndEnvThenOpensTheNewStackOnceDeployed() = createTest {
        val vm = model()
        typed(vm, "web", file)
        vm.addEnvRow()
        vm.setEnvRow(0, EnvVar("A", "x=y"))
        vm.create()
        settle { vm.state.value.created != null }
        assertEquals(CreatedStack(12, "web"), vm.state.value.created)
        val body = createBodies.value.single()
        assertEquals(file, body["StackFileContent"]!!.jsonPrimitive.content)
        assertEquals("web", body["Name"]!!.jsonPrimitive.content)
        assertEquals("""[{"name":"A","value":"x=y"}]""", body["Env"].toString())
        assertTrue("POST /api/stacks/create/standalone/string" in requests)
        assertEquals(2, requests.count { it == "GET /api/stacks/12" }, "polled past deploying")
        vm.navigated()
        assertNull(container.stackSessions.progress(StackKey("s1", 1, "web")).value)
    }

    @Test
    fun yamlErrorBlocksCreate() = createTest {
        val vm = model()
        typed(vm, "web", "services:\n  web: [\n")
        assertNotNull(vm.state.value.yamlError)
        assertFalse(vm.state.value.canCreate)
        vm.create()
        assertTrue(createBodies.value.isEmpty())
    }

    @Test
    fun structuralWarningsShowButDoNotBlock() = createTest {
        val vm = model()
        typed(vm, "web", "volumes:\n  data: {}\n")
        assertTrue(vm.state.value.warnings.isNotEmpty())
        assertTrue(vm.state.value.canCreate)
        vm.create()
        settle { vm.state.value.created != null }
    }

    @Test
    fun protectedImageInTheNewFileWarnsButIsAllowed() = createTest {
        val vm = model()
        typed(vm, "tools", "services:\n  ui:\n    image: portainer/portainer-ce:2.45.3\n")
        assertEquals(PROTECTED_IMAGE_CREATE_WARNING, vm.state.value.protectedWarning)
        assertTrue(vm.state.value.canCreate)
        vm.create()
        settle { vm.state.value.created != null }
        assertEquals(1, createBodies.value.size)
    }

    @Test
    fun failureKeepsTheForm() = createTest {
        createStatus = HttpStatusCode.InternalServerError
        val vm = model()
        typed(vm, "web", file)
        vm.create()
        settle { vm.state.value.failure != null }
        val s = vm.state.value
        assertNull(s.created)
        assertFalse(s.failure!!.contains(key))
        assertEquals("web", s.name)
        assertEquals(file, s.text)
        assertNull(s.failedStackId)
    }

    @Test
    fun deployErrorAfterTheAsyncCreateIsAFailureThatNamesTheStack() = createTest {
        statuses.value = listOf(3, 4)
        statusMessage = "pull access denied for nginx"
        val vm = model()
        typed(vm, "web", file)
        vm.create()
        settle { vm.state.value.failure != null }
        assertEquals("pull access denied for nginx", vm.state.value.failure)
        assertEquals(12, vm.state.value.failedStackId)
        assertNull(vm.state.value.created)
        assertEquals(file, vm.state.value.text)
    }

    @Test
    fun repositoryCreateSendsGitSettingsAndForgetsThePassword() = createTest {
        val vm = model()
        vm.setMethod(CreateMethod.Repository)
        vm.setName("app")
        vm.editGit { copy(url = "https://git.example/app.git", referenceName = "refs/heads/main", configFilePath = "compose.yml", authEnabled = true, username = "deploy") }
        assertTrue(PASSWORD_REQUIRED_ERROR in vm.state.value.gitErrors)
        assertFalse(vm.state.value.canCreate)
        vm.editGit { copy(password = "s3cret-token") }
        // 2.45: polling lives on a git source the new stack doesn't have yet; only the webhook is offered.
        vm.setAutoUpdateEnabled(true)
        assertEquals(AutoUpdateMechanism.Webhook, vm.state.value.git.mechanism)
        vm.setMechanism(AutoUpdateMechanism.Polling)
        assertEquals(AutoUpdateMechanism.Webhook, vm.state.value.git.mechanism)
        assertTrue(vm.state.value.canCreate)
        createGate = CompletableDeferred()
        vm.create()
        settle { vm.state.value.running }
        assertEquals("", vm.state.value.git.password, "the typed password leaves the state once sent")
        val op = assertIs<StackOp.Create.Repository>(container.stackSessions.progress(StackKey("s1", 1, "app")).value!!.op)
        assertNull(op.request.auth!!.password)
        createGate!!.complete(Unit)
        settle { vm.state.value.created != null }
        val body = createBodies.value.single()
        assertEquals("https://git.example/app.git", body["RepositoryURL"]!!.jsonPrimitive.content)
        assertEquals("s3cret-token", body["RepositoryPassword"]!!.jsonPrimitive.content)
        assertEquals("deploy", body["RepositoryUsername"]!!.jsonPrimitive.content)
        assertEquals("compose.yml", body["ComposeFile"]!!.jsonPrimitive.content)
        assertEquals("05de31a2-79fa-4644-9c12-faa67e5c49f0", body["AutoUpdate"]!!.jsonObject["Webhook"]!!.jsonPrimitive.content)
        assertTrue("POST /api/stacks/create/standalone/repository" in requests)
    }

    @Test
    fun pollingIsOfferedAtCreateBefore244() = createTest {
        version = "2.43.0"
        val vm = model()
        vm.setMethod(CreateMethod.Repository)
        vm.setAutoUpdateEnabled(true)
        vm.setMechanism(AutoUpdateMechanism.Polling)
        assertEquals(AutoUpdateMechanism.Polling, vm.state.value.git.mechanism)
    }

    @Test
    fun customTemplatesAreDockerStandaloneOnlyAndOpenInTheEditorWithVariablesFilled() = createTest {
        val vm = model()
        vm.setMethod(CreateMethod.Template)
        settle { !vm.state.value.loadingTemplates }
        val templates = assertNotNull(vm.state.value.templates)
        assertEquals(listOf(3, 1), templates.map { it.id }, "type 2 only, by title")
        vm.selectTemplate(templates.single { it.id == 1 })
        settle { vm.state.value.templateFile != null }
        vm.useTemplate()
        val s = vm.state.value
        assertEquals(CreateMethod.Editor, s.method)
        // CE has no variable inputs (Business Edition only): each variable's default, else the tag as `{{ NAME }}`.
        assertEquals(
            "services:\n  web:\n    image: nginx:1.27\n    ports:\n      - \"{{ PORT }}:80\"\n    # {{ UNSET }} {{ OTHER }}\n",
            s.text,
        )
        assertTrue(s.fromTemplate)
        settle { !vm.state.value.validating }
        vm.setName("web")
        vm.create()
        settle { vm.state.value.created != null }
        assertEquals("true", createBodies.value.single()["FromAppTemplate"].toString())
    }

    @Test
    fun gitBackedTemplatesAreFetchedFromTheirRepository() = createTest {
        val vm = model()
        vm.setMethod(CreateMethod.Template)
        settle { !vm.state.value.loadingTemplates }
        vm.selectTemplate(vm.state.value.templates!!.single { it.id == 3 })
        settle { vm.state.value.templateFile != null }
        assertEquals(file, vm.state.value.templateFile)
        assertTrue("PUT /api/custom_templates/3/git_fetch" in requests)
    }

    @Test
    fun aCreateThatFailedWithoutAnIdOffersTheStackIfPortainerMadeItAnyway() = createTest {
        createStatus = HttpStatusCode.InternalServerError
        createdAnyway = true
        val vm = model()
        typed(vm, "web", file)
        vm.create()
        settle { vm.state.value.failedStackId != null }
        assertEquals(12, vm.state.value.failedStackId)
    }

    @Test
    fun aCreateRefusedAsANameConflictDoesNotLookForAStackMadeAnyway() = createTest {
        createStatus = HttpStatusCode.Conflict
        createdAnyway = true // a stack of that name is listed: another one, which the 409 is about
        val vm = model()
        typed(vm, "web", file)
        val listsBefore = requests.count { it == "GET /api/stacks" }
        vm.create()
        settle { vm.state.value.failure != null }
        drain()
        assertNull(vm.state.value.failedStackId)
        // Only the name check before the create read the list; nothing looked it up after the 409.
        assertEquals(listsBefore + 1, requests.count { it == "GET /api/stacks" }, requests.toString())
    }

    @Test
    fun anUnreadableVersionRefusesARepositoryCreateUntilItIsReadAgain() = createTest {
        statusFails = true
        val vm = model()
        assertTrue(vm.state.value.versionUnknown)
        vm.setMethod(CreateMethod.Repository)
        vm.setName("app")
        vm.editGit { copy(url = "https://git.example/app.git") }
        assertTrue(vm.state.value.canCreate)
        vm.create()
        assertEquals(VERSION_UNKNOWN_MESSAGE, vm.state.value.error)
        settle { requests.count { it == "GET /api/status" } == 2 }
        drain()
        assertTrue(createBodies.value.isEmpty())
        assertTrue(vm.state.value.versionUnknown)
        // Once the version can be read again, the next try goes ahead.
        statusFails = false
        vm.clearError()
        vm.create() // still refused; the version is read again meanwhile
        assertEquals(VERSION_UNKNOWN_MESSAGE, vm.state.value.error)
        settle { !vm.state.value.versionUnknown }
        vm.clearError()
        vm.create()
        settle { vm.state.value.created != null }
        assertEquals(1, createBodies.value.size)
    }

    @Test
    fun anUnreadableVersionStillAllowsAWebEditorCreate() = createTest {
        statusFails = true
        val vm = model()
        typed(vm, "web", file)
        vm.create()
        settle { vm.state.value.created != null }
    }

    @Test
    fun aRefusedRepositoryCreateKeepsThePassword() = createTest {
        val vm = model()
        vm.setMethod(CreateMethod.Repository)
        vm.setName("app")
        vm.editGit { copy(url = "https://git.example/app.git", authEnabled = true, username = "deploy", password = "s3cret-token") }
        assertTrue(container.stackSessions.start(StackKey("s1", 1, "shop"), 1, StackOp.Stop))
        vm.create()
        settle { vm.state.value.error != null }
        assertEquals("s3cret-token", vm.state.value.git.password)
        stopGate.complete(Unit)
        settle { !container.stackSessions.isRunning("s1", 1) }
    }

    @Test
    fun templateRenderingFollowsPortainersRule() {
        val vars = listOf(TemplateVariable("A", "a", "dflt"), TemplateVariable("B", "b", ""), TemplateVariable("C.D", "c", ""))
        assertEquals(
            "x=typed y=typed z={{ B }} w={{ B }} v=val raw=typed amp=typed",
            renderCustomTemplate("x={{A}} y={{ A }} z={{ B }} w={{B}} v={{ C.D }} raw={{{ A }}} amp={{& A}}", vars, mapOf("A" to "typed", "C.D" to "val")),
        )
        assertEquals("p={{ B }}", renderCustomTemplate("p={{ B }}", vars, emptyMap()))
        assertEquals("q=dflt", renderCustomTemplate("q={{A}}", vars, mapOf("A" to "")))
    }

    @Test
    fun uploadLoadsTheFileIntoTheEditorWithLineEndsNormalisedOnce() = createTest {
        val vm = model()
        vm.setMethod(CreateMethod.Upload)
        vm.loadFile(PickedFile.Text("app.yml", "services:\r\n  web:\r\n    image: nginx\r\n"))
        val s = vm.state.value
        assertEquals(CreateMethod.Editor, s.method)
        assertEquals(file, s.text)
        assertEquals("app.yml", s.sourceName)
        vm.loadFile(PickedFile.Failed(FILE_TOO_LARGE_MESSAGE))
        assertEquals(FILE_TOO_LARGE_MESSAGE, vm.state.value.error)
        assertEquals(file, vm.state.value.text)
    }

    @Test
    fun pickedFilesMustBeSmallUtf8Text() {
        assertEquals(PickedFile.Text("a.yml", "services: {}\n"), decodePickedFile("a.yml", "﻿services: {}\n".encodeToByteArray()))
        assertEquals(PickedFile.Failed(FILE_TOO_LARGE_MESSAGE), decodePickedFile("big.yml", ByteArray(MAX_PICKED_FILE_BYTES + 1) { 'a'.code.toByte() }))
        assertIs<PickedFile.Text>(decodePickedFile("max.yml", ByteArray(MAX_PICKED_FILE_BYTES) { 'a'.code.toByte() }))
        assertEquals(PickedFile.Failed(FILE_NOT_TEXT_MESSAGE), decodePickedFile("x.bin", byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals(PickedFile.Failed(FILE_NOT_TEXT_MESSAGE), decodePickedFile("x.bin", byteArrayOf(0x61, 0x00, 0x62)))
    }

    @Test
    fun createIsRefusedWhileAnotherStackOperationRunsOnTheEndpoint() = createTest {
        val vm = model()
        typed(vm, "web", file)
        assertTrue(container.stackSessions.start(StackKey("s1", 1, "shop"), 1, StackOp.Stop))
        vm.create()
        settle { vm.state.value.error != null }
        assertEquals(STACK_OPERATION_IN_PROGRESS_MESSAGE, vm.state.value.error)
        assertTrue(createBodies.value.isEmpty())
        stopGate.complete(Unit)
        settle { !container.stackSessions.isRunning("s1", 1) }
    }

    @Test
    fun aNewScreenReattachesToARunningCreate() = createTest {
        createGate = CompletableDeferred()
        val c = env().container
        val first = model(c)
        typed(first, "web", file)
        first.create()
        settle { first.state.value.running }
        val second = model(c)
        settle { second.state.value.running }
        assertEquals("web", second.state.value.name)
        assertEquals(file, second.state.value.text)
        createGate!!.complete(Unit)
        settle { second.state.value.created != null }
        assertEquals(CreatedStack(12, "web"), second.state.value.created)
    }

    @Test
    fun theNewStacksDetailConsumesACreateTheScreenWasLeftDuring() = createTest {
        val c = env().container
        val vm = model(c)
        typed(vm, "web", file)
        vm.create()
        settle { vm.state.value.created != null }
        // The create screen is gone before it could open the stack; the stack's own screen opens later.
        val detail = StackDetailViewModel(c, "s1", 1, 12, "web")
        settle { !detail.state.value.loading && c.stackSessions.progress(StackKey("s1", 1, "web")).value == null }
        assertNull(detail.state.value.notice)
        assertNull(detail.state.value.failure)
        drain { !detail.state.value.loading }
    }
}
