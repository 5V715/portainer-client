package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.settle
import dev.silas.portainer.app.support.CONNECTION_DEPENDENCY_REMINDER
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.stacks.ComposeWarning
import dev.silas.portainer.core.stacks.envDiff
import dev.silas.portainer.core.stacks.lineDiff
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
class StackEditorViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    private val putBodies = MutableStateFlow(emptyList<JsonObject>())

    private val original = "services:\n  web:\n    image: nginx\n    ports:\n      - \"80:80\"\n"
    @Volatile private var fileContent = original
    @Volatile private var fileFails = false
    @Volatile private var git = false
    @Volatile private var version = "2.45.3"
    @Volatile private var env = """[{"name":"A","value":"1"},{"name":"DB_PASSWORD","value":"hunter2"}]"""
    @Volatile private var putStatus = HttpStatusCode.OK
    @Volatile private var putNetworkLoss = false
    @Volatile private var putGate: CompletableDeferred<Unit>? = null
    private val key = "secret-api-key"
    private var testEnv: TestEnv? = null

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() {
        putGate?.complete(Unit)
        Dispatchers.resetMain()
    }

    private fun stackJson(): String {
        val gitConfig = if (git) ""","GitConfig":{"URL":"https://g/x.git","ReferenceName":"refs/heads/main","ConfigFilePath":"compose.yml"}""" else ""
        return """{"Id":5,"Name":"shop","Type":2,"EndpointId":1,"Status":1,"Env":$env,"Option":{"Prune":true}$gitConfig}"""
    }

    private suspend fun env(): TestEnv {
        val e = TestEnv { req ->
            val path = req.url.encodedPath
            requestLog.update { it + "${req.method.value} $path" }
            when {
                path == "/api/status" -> respond("""{"Version":"$version"}""", HttpStatusCode.OK, json)
                path == "/api/stacks" -> respond("[]", HttpStatusCode.OK, json)
                path == "/api/stacks/5" && req.method == HttpMethod.Put -> {
                    val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
                    putBodies.update { it + body }
                    putGate?.await()
                    if (putNetworkLoss) throw RuntimeException("connection reset $key")
                    if (putStatus != HttpStatusCode.OK) {
                        respond("""{"message":"compose up failed","details":"bad $key"}""", putStatus, json)
                    } else {
                        fileContent = body["StackFileContent"]!!.jsonPrimitive.content
                        env = body["Env"].toString()
                        respond(stackJson(), HttpStatusCode.OK, json)
                    }
                }
                path == "/api/stacks/5" -> respond(stackJson(), HttpStatusCode.OK, json)
                path == "/api/stacks/5/file" ->
                    if (fileFails) {
                        respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json)
                    } else {
                        respond("""{"StackFileContent":${Json.encodeToString(String.serializer(), fileContent)}}""", HttpStatusCode.OK, json)
                    }
                path.endsWith("/containers/json") -> respond(
                    """[{"Id":"c1","Names":["/shop-web-1"],"Image":"nginx","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"shop"}}]""",
                    HttpStatusCode.OK,
                    json,
                )
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        e.repo.save(ServerConfig("s1", "A", "https://a.example.com"), key)
        testEnv = e
        return e
    }

    private val container: AppContainer get() = testEnv!!.container

    private val details = mutableListOf<StackDetailViewModel>()
    private val editors = mutableListOf<StackEditorViewModel>()

    /**
     * Runs [block], then waits until every view model it made is idle: their loads and validations hop to
     * Dispatchers.Default, and one resuming after the test's resetMain would fail the next test.
     */
    private fun editorTest(block: suspend TestScope.() -> Unit) = runTest {
        block()
        settle {
            details.all { !it.state.value.loading && it.state.value.running == null && !it.state.value.starting } &&
                editors.all { e -> e.state.value.let { !it.validating && !it.checking && !it.updating } }
        }
        // A load cancelled by a newer one may still be finishing its Dispatchers.Default part: let it land.
        repeat(5) {
            advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(20) }
        }
        advanceUntilIdle()
    }

    private fun editorOf(c: AppContainer, detail: StackDetailViewModel) =
        StackEditorViewModel(c, detail, "s1", 1, "shop").also { editors += it }

    private suspend fun TestScope.loaded(stackId: Int? = 5): Pair<StackDetailViewModel, StackEditorViewModel> {
        val c = env().container
        val detail = StackDetailViewModel(c, "s1", 1, stackId, "shop").also { details += it }
        val editor = editorOf(c, detail)
        settle { !detail.state.value.loading && detail.state.value.stack != null }
        settle { !editor.state.value.editable || (editor.state.value.ready && !editor.state.value.validating) }
        return detail to editor
    }

    private suspend fun TestScope.validated(editor: StackEditorViewModel) = settle { !editor.state.value.validating }

    private suspend fun TestScope.idle(editor: StackEditorViewModel) =
        settle { !editor.state.value.updating && !editor.state.value.checking }

    private val newFile = "services:\n  web:\n    image: nginx:1.27\n    ports:\n      - \"80:80\"\n"

    @Test
    fun reviewListsLineDiffHunksAndEnvChanges() = editorTest {
        val (_, editor) = loaded()
        val s0 = editor.state.value
        assertTrue(s0.editable)
        assertEquals(original, s0.text)
        assertEquals(listOf(EnvVar("A", "1"), EnvVar("DB_PASSWORD", "hunter2")), s0.env)
        assertFalse(s0.hasUnsavedChanges)

        editor.setText(newFile)
        editor.setEnvRow(0, EnvVar("A", "2"))
        editor.addEnvRow()
        editor.setEnvRow(2, EnvVar("NEW", "x"))
        validated(editor)
        assertTrue(editor.state.value.canUpdate)
        editor.requestUpdate()
        val review = assertNotNull(editor.state.value.review)
        assertEquals(lineDiff(original, newFile), review.hunks)
        val newEnv = listOf(EnvVar("A", "2"), EnvVar("DB_PASSWORD", "hunter2"), EnvVar("NEW", "x"))
        assertEquals(envDiff(listOf(EnvVar("A", "1"), EnvVar("DB_PASSWORD", "hunter2")), newEnv), review.envChanges)
        assertEquals(CONNECTION_DEPENDENCY_REMINDER, review.reminder)
        assertFalse(review.pullImage)
        // Prune exists on 2.45.3 and starts from the stack's saved Option.Prune.
        assertTrue(review.pruneAvailable)
        assertTrue(review.prune)
        assertTrue(requests.none { it.startsWith("PUT") }, "the review alone sends nothing")
    }

    @Test
    fun yamlErrorDisablesUpdateAndMarksTheLine() = editorTest {
        val (_, editor) = loaded()
        editor.setText("services:\n  web:\n    image: [nginx\n")
        validated(editor)
        val s = editor.state.value
        assertNotNull(s.yamlError)
        assertEquals(s.yamlError!!.line, s.errorLine)
        assertFalse(s.canUpdate)
        editor.requestUpdate()
        assertNull(editor.state.value.review)
    }

    @Test
    fun validationIsDebouncedAndUpdateWaitsForIt() = editorTest {
        val (_, editor) = loaded()
        editor.setText("services: {}\n")
        advanceTimeBy(200)
        runCurrent()
        assertTrue(editor.state.value.validating)
        assertFalse(editor.state.value.canUpdate)
        editor.setText(newFile)
        advanceTimeBy(250)
        runCurrent()
        assertTrue(editor.state.value.validating, "a new keystroke restarts the 300 ms wait")
        validated(editor)
        assertTrue(editor.state.value.canUpdate)
    }

    @Test
    fun structuralWarningsAreShownButAllowUpdate() = editorTest {
        val (_, editor) = loaded()
        editor.setText("services:\n  web:\n    build: .\n  worker:\n    restart: always\n")
        validated(editor)
        val s = editor.state.value
        assertNull(s.yamlError)
        assertEquals(
            listOf(ComposeWarning.BuildWithoutContext("web"), ComposeWarning.ServiceWithoutImage("worker")),
            s.warnings,
        )
        assertTrue(s.canUpdate)
        editor.requestUpdate()
        assertEquals(s.warnings, assertNotNull(editor.state.value.review).warnings)
    }

    @Test
    fun newFileAddingAProtectedImageIsBlocked() = editorTest {
        val (_, editor) = loaded()
        editor.setText(newFile + "  agent:\n    image: portainer/agent:2.45.3\n")
        validated(editor)
        editor.requestUpdate()
        assertNull(editor.state.value.review)
        assertEquals(PROTECTED_IMAGE_MESSAGE, editor.state.value.error)
        assertTrue(requests.none { it.startsWith("PUT") })
    }

    @Test
    fun protectionUnknownAtConfirmBlocksTheUpdate() = editorTest {
        val (_, editor) = loaded()
        editor.setText(newFile)
        validated(editor)
        editor.requestUpdate()
        assertNotNull(editor.state.value.review)
        fileFails = true // the re-check before starting can't read the current file
        editor.confirmUpdate()
        idle(editor)
        assertEquals(PROTECTION_UNKNOWN_MESSAGE, editor.state.value.error)
        assertTrue(requests.none { it.startsWith("PUT") })
        assertEquals(newFile, editor.state.value.text)
    }

    @Test
    fun duplicateEnvNamesAreAnErrorInRowsAndBlock() = editorTest {
        val (_, editor) = loaded()
        editor.addEnvRow()
        editor.setEnvRow(2, EnvVar("A", "again"))
        assertEquals(listOf("Duplicate name: A"), editor.state.value.envErrors)
        assertFalse(editor.state.value.canUpdate)

        editor.setEnvMode(EnvMode.Block)
        assertEquals(EnvMode.Block, editor.state.value.envMode)
        assertEquals("A=1\nDB_PASSWORD=hunter2\nA=again\n", editor.state.value.envBlock)
        assertEquals(listOf("Duplicate name: A"), editor.state.value.envErrors)
        editor.setEnvBlock("A=1\nbroken line\n")
        assertEquals(1, editor.state.value.envErrors.size)
        // Rows can't take a block with errors: the switch is refused and the text kept.
        editor.setEnvMode(EnvMode.Rows)
        assertEquals(EnvMode.Block, editor.state.value.envMode)
        editor.setEnvBlock("A=1\nB=\"two # words\"\n")
        editor.setEnvMode(EnvMode.Rows)
        assertEquals(EnvMode.Rows, editor.state.value.envMode)
        assertEquals(listOf(EnvVar("A", "1"), EnvVar("B", "two # words")), editor.state.value.env)
        assertTrue(editor.state.value.envErrors.isEmpty())
    }

    @Test
    fun unsavedChangesMakeBackAsk() = editorTest {
        val (_, editor) = loaded()
        assertTrue(editor.requestBack(), "nothing changed: leave at once")
        editor.setText(newFile)
        assertFalse(editor.requestBack())
        assertTrue(editor.state.value.confirmDiscard)
        editor.dismissDiscard()
        assertFalse(editor.state.value.confirmDiscard)
        editor.setText(original)
        assertTrue(editor.requestBack(), "edited back to the original")
        editor.setEnvRow(1, EnvVar("DB_PASSWORD", "changed"))
        assertFalse(editor.requestBack())
    }

    @Test
    fun crlfIsNormalisedOnceOnLoadAndTypedTextIsSentAsIs() = editorTest {
        fileContent = original.replace("\n", "\r\n")
        val (_, editor) = loaded()
        assertEquals(original, editor.state.value.text)
        assertFalse(editor.state.value.hasUnsavedChanges)
        editor.setText(newFile + "# keep\r\n")
        assertEquals(newFile + "# keep\r\n", editor.state.value.text, "typing is never transformed")
        validated(editor)
        editor.requestUpdate()
        assertTrue(assertNotNull(editor.state.value.review).lineEndingsChanged)
        editor.confirmUpdate()
        settle { putBodies.value.isNotEmpty() }
        assertEquals(newFile + "# keep\r\n", putBodies.value.single()["StackFileContent"]!!.jsonPrimitive.content)
        idle(editor)
    }

    @Test
    fun updateRunsThroughStackSessionsAsStackOpUpdate() = editorTest {
        val (detail, editor) = loaded()
        editor.setText(newFile)
        editor.setEnvRow(0, EnvVar("A", "2"))
        validated(editor)
        editor.requestUpdate()
        editor.setPullImage(true)
        editor.setPrune(false)
        putGate = CompletableDeferred()
        editor.confirmUpdate()
        settle { putBodies.value.isNotEmpty() }
        settle { editor.state.value.updating }
        val p = assertNotNull(container.stackSessions.progress(StackKey("s1", 1, "shop")).value)
        assertTrue(p.running)
        val op = assertIs<StackOp.Update>(p.op)
        assertEquals(newFile, op.file)
        assertEquals(listOf(EnvVar("A", "2"), EnvVar("DB_PASSWORD", "hunter2")), op.env)
        assertTrue(op.pullImage)
        assertFalse(op.prune)
        assertFalse(op.toString().contains("hunter2"), "secrets never reach toString")
        assertTrue(container.stackSessions.isRunning("s1", 1))
        settle { detail.state.value.running is StackOp.Update }
        assertTrue(editor.requestBack(), "a running update carries on without the screen")

        val body = putBodies.value.single()
        assertEquals("true", body["RepullImageAndRedeploy"].toString())
        assertEquals("true", body["PullImage"].toString())
        assertFalse("Prune" in body)
        assertEquals(2, body["Env"]!!.jsonArray.size)
        putGate!!.complete(Unit)
        idle(editor)
    }

    @Test
    fun successRefreshesDetailAndTheEditorBaseline() = editorTest {
        val (detail, editor) = loaded()
        editor.setText(newFile)
        validated(editor)
        editor.requestUpdate()
        val before = requests.count { it == "GET /api/stacks/5/file" }
        editor.confirmUpdate()
        idle(editor)
        settle { editor.state.value.notice != null }
        assertEquals("Stack updated", editor.state.value.notice)
        assertNull(editor.state.value.updateError)
        settle { detail.state.value.file == newFile && !detail.state.value.loading }
        assertTrue(requests.count { it == "GET /api/stacks/5/file" } > before + 1, "detail read the file again after the update")
        assertNull(detail.state.value.failure)
        settle { editor.state.value.original == newFile }
        assertFalse(editor.state.value.hasUnsavedChanges)
        editor.clearNotice()
        assertNull(container.stackSessions.progress(StackKey("s1", 1, "shop")).value)
    }

    @Test
    fun serverErrorKeepsFileAndEnvAndShowsARedactedError() = editorTest {
        putStatus = HttpStatusCode.InternalServerError
        failedUpdateKeepsEdits()
    }

    @Test
    fun networkLossKeepsFileAndEnvAndShowsARedactedError() = editorTest {
        putNetworkLoss = true
        failedUpdateKeepsEdits()
    }

    private suspend fun TestScope.failedUpdateKeepsEdits() {
        val (detail, editor) = loaded()
        editor.setText(newFile)
        editor.setEnvRow(0, EnvVar("A", "2"))
        validated(editor)
        editor.requestUpdate()
        editor.confirmUpdate()
        idle(editor)
        settle { editor.state.value.updateError != null }
        val s = editor.state.value
        assertFalse(s.updateError!!.contains(key), s.updateError)
        assertNull(s.notice)
        assertEquals(newFile, s.text)
        assertEquals(listOf(EnvVar("A", "2"), EnvVar("DB_PASSWORD", "hunter2")), s.env)
        assertTrue(s.hasUnsavedChanges)
        // The overview doesn't show the failure a second time; the editor owns it.
        settle { detail.state.value.running == null && !detail.state.value.loading }
        assertNull(detail.state.value.failure)
        assertNull(detail.state.value.notice)

        // A new editor (the screen was left and opened again) gets the edits back from the failed session.
        val again = editorOf(container, detail)
        settle { again.state.value.ready && again.state.value.updateError != null }
        assertEquals(newFile, again.state.value.text)
        assertEquals(listOf(EnvVar("A", "2"), EnvVar("DB_PASSWORD", "hunter2")), again.state.value.env)
        again.clearUpdateError()
        assertNull(container.stackSessions.progress(StackKey("s1", 1, "shop")).value)
    }

    @Test
    fun pruneIsHiddenOnServersWithoutIt() = editorTest {
        version = "2.39.8"
        val (_, editor) = loaded()
        editor.setText(newFile)
        validated(editor)
        editor.requestUpdate()
        val review = assertNotNull(editor.state.value.review)
        assertFalse(review.pruneAvailable)
        assertFalse(review.prune)
    }

    @Test
    fun externalAndGitStacksHaveNoEditor() = editorTest {
        val (_, external) = loaded(stackId = null)
        assertFalse(external.state.value.editable)
        external.setText("x: 1\n")
        assertEquals("", external.state.value.text)
        external.requestUpdate()
        assertNull(external.state.value.review)

        git = true
        val (detail, gitEditor) = loaded()
        assertFalse(gitEditor.state.value.editable)
        assertNotNull(detail.state.value.file, "git stacks still show their file (read-only)")
    }

    @Test
    fun detailTabsByStackKind() = editorTest {
        val (detail, _) = loaded()
        assertEquals(listOf(StackDetailTab.Overview, StackDetailTab.Editor), stackDetailTabs(detail.state.value))
        git = true
        val (gitDetail, _) = loaded()
        assertEquals(listOf(StackDetailTab.Overview, StackDetailTab.File), stackDetailTabs(gitDetail.state.value))
        val (external, _) = loaded(stackId = null)
        assertEquals(listOf(StackDetailTab.Overview), stackDetailTabs(external.state.value))
    }
}
