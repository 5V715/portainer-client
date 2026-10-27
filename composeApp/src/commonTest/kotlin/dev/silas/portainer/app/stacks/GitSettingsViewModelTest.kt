package dev.silas.portainer.app.stacks

import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.TestEnv
import dev.silas.portainer.app.drain
import dev.silas.portainer.app.settle
import dev.silas.portainer.app.support.CONNECTION_DEPENDENCY_REMINDER
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.api.SOURCE_SAVED_STACK_NOT_MESSAGE
import dev.silas.portainer.core.stacks.envDiff
import dev.silas.portainer.core.stacks.stackFeatures
import dev.silas.portainer.core.storage.ServerConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
class GitSettingsViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requestLog = MutableStateFlow(emptyList<String>())
    private val requests: List<String> get() = requestLog.value
    /** Bodies of every non-GET request, by "METHOD path". */
    private val bodies = MutableStateFlow(emptyList<Pair<String, JsonObject>>())

    @Volatile private var version = "2.42.0"
    @Volatile private var status = 1
    @Volatile private var auth = true
    @Volatile private var git = true
    @Volatile private var autoUpdate = """{"Interval":"5m","Webhook":"","ForceUpdate":true,"ForcePullImage":false}"""
    @Volatile private var image = "nginx"
    @Volatile private var env = """[{"name":"A","value":"1"},{"name":"DB_PASSWORD","value":"hunter2"}]"""
    @Volatile private var fileFails = false
    /** POST /git answers 500 with a message that echoes what was sent as the password. */
    @Volatile private var settingsFailEcho = false
    private val apiKey = "secret-api-key"
    private var testEnv: TestEnv? = null

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** The stack has a GitOps Source (only where the server has them). */
    @Volatile private var hasSource = true
    private val sourced: Boolean get() = hasSource && stackFeatures(version).savedGitCredentials

    private fun stackJson(id: Int = 5, sourceId: Int = 7): String {
        val authJson = if (auth) ""","Authentication":{"Username":"deploy","Password":""}""" else ""
        val gitJson = if (git) {
            ""","GitConfig":{"URL":"https://git.example/app.git","ReferenceName":"refs/heads/main","ConfigFilePath":"compose.yml"$authJson,"TLSSkipVerify":true},
            "AdditionalFiles":["override.yml"],"AutoUpdate":$autoUpdate""" + (if (sourced) ""","GitSourceId":$sourceId""" else "")
        } else {
            ""
        }
        return """{"Id":$id,"Name":"${if (id == 5) "shop" else "s$id"}","Type":2,"EndpointId":1,"Status":$status,
            "Env":${if (id == 5) env else "[]"},"Option":{"Prune":true}$gitJson}"""
    }

    private suspend fun env(): TestEnv {
        val e = TestEnv { req ->
            val path = req.url.encodedPath
            requestLog.update { it + "${req.method.value} $path" }
            if (req.method != HttpMethod.Get) {
                val text = (req.body as? TextContent)?.text
                bodies.update { it + ("${req.method.value} $path" to (text?.let { t -> Json.parseToJsonElement(t).jsonObject } ?: JsonObject(emptyMap()))) }
            }
            when {
                path == "/api/status" -> respond("""{"Version":"$version"}""", HttpStatusCode.OK, json)
                path == "/api/stacks" -> respond("[${stackJson(5)},${stackJson(6)},${stackJson(8)},${stackJson(9, sourceId = 3)}]", HttpStatusCode.OK, json)
                path == "/api/stacks/6" || path == "/api/stacks/8" -> respond(stackJson(path.substringAfterLast('/').toInt()), HttpStatusCode.OK, json)
                path == "/api/stacks/9" -> respond(stackJson(9, sourceId = 3), HttpStatusCode.OK, json)
                path == "/api/stacks/5/git" -> {
                    if (settingsFailEcho) {
                        val sent = bodies.value.last().second["RepositoryPassword"]?.toString()?.trim('"').orEmpty()
                        respond("""{"message":"authentication failed","details":"bad credentials $sent $apiKey"}""", HttpStatusCode.InternalServerError, json)
                    } else {
                        env = bodies.value.last().second["Env"].toString()
                        respond(stackJson(), HttpStatusCode.OK, json)
                    }
                }
                path == "/api/stacks/5/git/redeploy" -> {
                    env = bodies.value.last().second["Env"].toString()
                    status = 1
                    respond(stackJson(), HttpStatusCode.OK, json)
                }
                path == "/api/stacks/5" && req.method == HttpMethod.Put -> {
                    git = false
                    status = 1
                    respond(stackJson(), HttpStatusCode.OK, json)
                }
                path == "/api/stacks/5" -> respond(stackJson(), HttpStatusCode.OK, json)
                path == "/api/stacks/5/file" ->
                    if (fileFails) {
                        respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json)
                    } else {
                        respond("""{"StackFileContent":"services:\n  web:\n    image: $image\n"}""", HttpStatusCode.OK, json)
                    }
                path.startsWith("/api/gitops/sources/") -> respond("{}", HttpStatusCode.OK, json)
                path.endsWith("/containers/json") -> respond(
                    """[{"Id":"c1","Names":["/shop-web-1"],"Image":"nginx","State":"running","Status":"Up","Labels":{"com.docker.compose.project":"shop"}}]""",
                    HttpStatusCode.OK,
                    json,
                )
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        e.repo.save(ServerConfig("s1", "A", "https://a.example.com"), apiKey)
        testEnv = e
        return e
    }

    private val container: AppContainer get() = testEnv!!.container
    private val details = mutableListOf<StackDetailViewModel>()
    private val gits = mutableListOf<GitSettingsViewModel>()

    private fun gitTest(block: suspend TestScope.() -> Unit) = runTest {
        block()
        drain {
            details.all { !it.state.value.loading && !it.state.value.starting } &&
                gits.all { g -> g.state.value.let { !it.checking && it.running == null && !it.counting } }
        }
    }

    private var webhookIds = 0

    private suspend fun TestScope.loaded(): Pair<StackDetailViewModel, GitSettingsViewModel> {
        val c = env().container
        val detail = StackDetailViewModel(c, "s1", 1, 5, "shop").also { details += it }
        val vm = GitSettingsViewModel(c, detail, "s1", 1, 5, "shop", newWebhookId = { "00000000-0000-4000-8000-00000000000${++webhookIds}" })
            .also { gits += it }
        settle { !detail.state.value.loading && detail.state.value.stack != null && vm.state.value.form != null && !vm.state.value.counting }
        return detail to vm
    }

    private suspend fun TestScope.finished(vm: GitSettingsViewModel) {
        settle { !vm.state.value.checking && vm.state.value.running == null }
        settle { container.stackSessions.progress(StackKey("s1", 1, "shop")).value?.running != true }
        settle { vm.state.value.notice != null || vm.state.value.failure != null || vm.state.value.error != null }
    }

    private fun sent(what: String): JsonObject = bodies.value.last { it.first == what }.second

    private suspend fun TestScope.save(vm: GitSettingsViewModel) {
        vm.requestSave()
        assertNotNull(vm.state.value.saveConfirm, vm.state.value.error ?: vm.state.value.errors.toString())
        vm.confirmSave()
        finished(vm)
    }

    @Test
    fun loadShowsTheSettingsWithAnEmptyPasswordAndPolling() = gitTest {
        val (_, vm) = loaded()
        val s = vm.state.value
        assertTrue(s.available)
        val f = assertNotNull(s.form)
        assertEquals("https://git.example/app.git", f.url, "shown read-only on every version")
        assertEquals("refs/heads/main", f.referenceName)
        assertEquals("compose.yml", f.configFilePath)
        assertEquals("override.yml", f.additionalFiles)
        assertTrue(f.authEnabled)
        assertEquals("deploy", f.username)
        assertEquals("", f.password)
        assertTrue(s.passwordStored, "the form shows an 'unchanged' hint")
        assertTrue(f.tlsSkipVerify)
        assertTrue(f.autoUpdateEnabled)
        assertEquals(AutoUpdateMechanism.Polling, f.mechanism)
        assertEquals("5m", f.interval)
        assertTrue(f.forceUpdate)
        assertEquals(listOf(EnvVar("A", "1"), EnvVar("DB_PASSWORD", "hunter2")), f.env)
        assertFalse(s.hasUnsavedChanges)
        assertFalse(s.features.refListing, "CE has no ref listing: the reference is a text field")
        assertNull(s.webhookUrl)
    }

    @Test
    fun intervalIsValidatedAsAGoDurationOfAtLeastAMinute() = gitTest {
        val (_, vm) = loaded()
        for (ok in listOf("1h", "1h30m", "90s", "1m", "10m")) {
            vm.edit { copy(interval = ok) }
            assertTrue(vm.state.value.errors.isEmpty(), "$ok: ${vm.state.value.errors}")
            assertTrue(vm.state.value.canSave, ok)
        }
        for (bad in listOf("30s", "5", "abc", "", "5 m", "-5m")) {
            vm.edit { copy(interval = bad) }
            assertEquals(listOf(INTERVAL_ERROR), vm.state.value.errors, bad)
            assertFalse(vm.state.value.canSave, bad)
        }
        // The interval only matters for polling.
        vm.setMechanism(AutoUpdateMechanism.Webhook)
        assertTrue(vm.state.value.errors.isEmpty())
    }

    @Test
    fun webhookShowsTheUrlBuiltFromTheServerAndSendsItsId() = gitTest {
        val (_, vm) = loaded()
        vm.setMechanism(AutoUpdateMechanism.Webhook)
        val id = "00000000-0000-4000-8000-000000000001"
        assertEquals(id, vm.state.value.form!!.webhookId)
        assertEquals("https://a.example.com/api/stacks/webhooks/$id", vm.state.value.webhookUrl)
        save(vm)
        assertEquals(
            Json.parseToJsonElement("""{"Interval":"","Webhook":"$id","ForceUpdate":true,"ForcePullImage":false}"""),
            sent("POST /api/stacks/5/git")["AutoUpdate"],
        )
    }

    @Test
    fun anExistingWebhookKeepsItsId() = gitTest {
        autoUpdate = """{"Interval":"","Webhook":"05de31a2-79fa-4644-9c12-faa67e5c49f0","ForceUpdate":false,"ForcePullImage":true}"""
        val (_, vm) = loaded()
        assertEquals(AutoUpdateMechanism.Webhook, vm.state.value.form!!.mechanism)
        assertEquals("https://a.example.com/api/stacks/webhooks/05de31a2-79fa-4644-9c12-faa67e5c49f0", vm.state.value.webhookUrl)
    }

    @Test
    fun savingWithoutTypingThePasswordKeepsTheStoredOne() = gitTest {
        // Review Focus 5: CE keeps the stored password for auth on + an empty password; nothing else is "unchanged".
        val (_, vm) = loaded()
        vm.edit { copy(tlsSkipVerify = false) }
        save(vm)
        assertEquals("Git settings saved", vm.state.value.notice)
        val body = sent("POST /api/stacks/5/git")
        assertEquals("true", body["RepositoryAuthentication"].toString())
        assertEquals("\"deploy\"", body["RepositoryUsername"].toString())
        assertEquals("\"\"", body["RepositoryPassword"].toString())
        assertEquals("false", body["TLSSkipVerify"].toString())
        // Everything else is the stack's current value.
        assertEquals("\"refs/heads/main\"", body["RepositoryReferenceName"].toString())
        assertEquals("\"compose.yml\"", body["ConfigFilePath"].toString())
        assertEquals("[\"override.yml\"]", body["AdditionalFiles"].toString())
        assertEquals("""[{"name":"A","value":"1"},{"name":"DB_PASSWORD","value":"hunter2"}]""", body["Env"].toString())
        assertEquals(Json.parseToJsonElement("""{"Interval":"5m","Webhook":"","ForceUpdate":true,"ForcePullImage":false}"""), body["AutoUpdate"])
        assertEquals("true", body["Prune"].toString())
    }

    @Test
    fun unchangedFieldsAreSentAsTheyAreOnEveryVersion() = gitTest {
        for (v in listOf("2.33.7", "2.39.8", "2.42.0")) {
            version = v
            bodies.value = emptyList()
            val (_, vm) = loaded()
            vm.edit { copy(username = "bob") }
            save(vm)
            val body = sent("POST /api/stacks/5/git")
            assertEquals("\"refs/heads/main\"", body["RepositoryReferenceName"].toString(), v)
            assertEquals(2, (body["Env"] as kotlinx.serialization.json.JsonArray).size, v)
            assertTrue(body["AutoUpdate"] is JsonObject, v)
            assertEquals("\"bob\"", body["RepositoryUsername"].toString(), v)
            assertEquals("\"\"", body["RepositoryPassword"].toString(), v)
            assertEquals("true", body["TLSSkipVerify"].toString(), v)
        }
    }

    @Test
    fun editedRefEnvAndAutoUpdateOffAreSent() = gitTest {
        val (_, vm) = loaded()
        vm.edit { copy(referenceName = "refs/tags/v2") }
        vm.setEnvRow(0, EnvVar("A", "2"))
        vm.setAutoUpdateEnabled(false)
        save(vm)
        val body = sent("POST /api/stacks/5/git")
        assertEquals("\"refs/tags/v2\"", body["RepositoryReferenceName"].toString())
        assertEquals("""[{"name":"A","value":"2"},{"name":"DB_PASSWORD","value":"hunter2"}]""", body["Env"].toString())
        assertEquals(JsonNull, body["AutoUpdate"])
    }

    @Test
    fun turningAuthOffSendsTheNoAuthForm() = gitTest {
        val (_, vm) = loaded()
        vm.edit { copy(authEnabled = false) }
        save(vm)
        val body = sent("POST /api/stacks/5/git")
        assertEquals("false", body["RepositoryAuthentication"].toString())
        assertEquals("\"\"", body["RepositoryPassword"].toString())
    }

    @Test
    fun turningAuthOnWithoutAStoredPasswordNeedsOne() = gitTest {
        auth = false
        val (_, vm) = loaded()
        assertFalse(vm.state.value.passwordStored)
        vm.edit { copy(authEnabled = true, username = "deploy") }
        assertEquals(listOf(PASSWORD_REQUIRED_ERROR), vm.state.value.errors)
        assertFalse(vm.state.value.canSave)
        vm.edit { copy(password = "t0ken") }
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun theTypedPasswordIsSentOnceAndNeverKept() = gitTest {
        val (_, vm) = loaded()
        vm.edit { copy(password = "s3cret-token") }
        assertTrue(vm.state.value.hasUnsavedChanges)
        assertFalse(vm.state.value.toString().contains("s3cret-token"), "state toString hides the password")
        vm.requestSave()
        assertFalse(vm.state.value.toString().contains("s3cret-token"))
        vm.confirmSave()
        // Gone from the view model's state as soon as the save is sent.
        assertEquals("", vm.state.value.form!!.password)
        finished(vm)
        assertEquals("\"s3cret-token\"", sent("POST /api/stacks/5/git")["RepositoryPassword"].toString())
        assertEquals("", vm.state.value.form!!.password)
        val p = assertNotNull(container.stackSessions.progress(StackKey("s1", 1, "shop")).value)
        assertFalse(p.toString().contains("s3cret-token"))
        assertNull(assertIs<StackOp.GitSettings>(p.op).request.auth!!.password)
    }

    @Test
    fun aFailedSaveNeverShowsThePasswordAndKeepsTheOtherEdits() = gitTest {
        settingsFailEcho = true
        val (_, vm) = loaded()
        vm.edit { copy(password = "s3cret-token", referenceName = "refs/heads/dev") }
        save(vm)
        val (op, message) = assertNotNull(vm.state.value.failure)
        assertIs<StackOp.GitSettings>(op)
        assertFalse(message.contains("s3cret-token"), message)
        assertFalse(message.contains(apiKey), message)
        assertEquals("refs/heads/dev", vm.state.value.form!!.referenceName, "a failed save keeps the edits")
        assertEquals("", vm.state.value.form!!.password)
        assertTrue(vm.state.value.hasUnsavedChanges)
        assertFalse(vm.state.value.toString().contains("s3cret-token"))
    }

    @Test
    fun onSourcedServersCredentialsGoToTheSourceWithASharedWarning() = gitTest {
        version = "2.45.3"
        val (_, vm) = loaded()
        assertEquals(2, vm.state.value.otherStacksOnSource, "stacks 6 and 8 use source 7; 9 uses another")
        // An unchanged save touches no source and sends no inline credentials.
        vm.setEnvRow(0, EnvVar("A", "2"))
        vm.requestSave()
        assertNull(vm.state.value.saveConfirm!!.sharedWarning)
        vm.confirmSave()
        finished(vm)
        val plain = sent("POST /api/stacks/5/git")
        assertEquals("7", plain["SourceID"].toString())
        assertFalse("RepositoryPassword" in plain)
        assertFalse("RepositoryAuthentication" in plain)
        assertTrue(requests.none { it.startsWith("PUT /api/gitops") })
        vm.clearNotice()

        settle { !vm.state.value.hasUnsavedChanges }
        vm.edit { copy(authEnabled = false) }
        vm.requestSave()
        assertEquals("This also changes 2 other stacks using this source.", vm.state.value.saveConfirm!!.sharedWarning)
        vm.confirmSave()
        finished(vm)
        assertEquals(Json.parseToJsonElement("""{"authentication":{}}"""), sent("PUT /api/gitops/sources/7"))
    }

    @Test
    fun keepingTheSourcePasswordLeavesItOutOfTheSourceUpdate() = gitTest {
        version = "2.45.3"
        val (_, vm) = loaded()
        vm.edit { copy(username = "bob") }
        save(vm)
        assertEquals(Json.parseToJsonElement("""{"authentication":{"username":"bob"}}"""), sent("PUT /api/gitops/sources/7"))
    }

    @Test
    fun from244ThePollingIntervalIsTheSources() = gitTest {
        version = "2.44.0"
        val (_, vm) = loaded()
        vm.edit { copy(interval = "10m") }
        vm.requestSave()
        assertEquals("This also changes 2 other stacks using this source.", vm.state.value.saveConfirm!!.sharedWarning)
        vm.confirmSave()
        finished(vm)
        assertEquals(Json.parseToJsonElement("""{"interval":"10m"}"""), sent("PUT /api/gitops/sources/7"))
        assertEquals("\"10m\"", (sent("POST /api/stacks/5/git")["AutoUpdate"] as JsonObject)["Interval"].toString())
    }

    @Test
    fun on243TheIntervalStaysOnTheStack() = gitTest {
        version = "2.43.0"
        val (_, vm) = loaded()
        vm.edit { copy(interval = "10m") }
        vm.requestSave()
        assertNull(vm.state.value.saveConfirm!!.sharedWarning)
        vm.confirmSave()
        finished(vm)
        assertTrue(requests.none { it.startsWith("PUT /api/gitops") })
    }

    @Test
    fun redeployReviewShowsRefPathsPullAndEnvChangesThenSendsCurrentValues() = gitTest {
        val (_, vm) = loaded()
        vm.setEnvRow(0, EnvVar("A", "2"))
        vm.requestRedeploy()
        val review = assertNotNull(vm.state.value.redeployReview)
        assertEquals("refs/heads/main", review.referenceName)
        assertNull(review.previousReferenceName)
        assertEquals(listOf("compose.yml", "override.yml"), review.composePaths)
        assertFalse(review.pullImage)
        assertEquals(
            envDiff(listOf(EnvVar("A", "1"), EnvVar("DB_PASSWORD", "hunter2")), listOf(EnvVar("A", "2"), EnvVar("DB_PASSWORD", "hunter2"))),
            review.envChanges,
        )
        assertEquals(CONNECTION_DEPENDENCY_REMINDER, review.reminder)
        assertEquals("Force redeployment: always (Portainer recreates the containers)", review.forceRedeployment)
        assertTrue(requests.none { it.startsWith("PUT") }, "the review alone sends nothing")
        vm.setRedeployPullImage(true)
        vm.confirmRedeploy()
        finished(vm)
        assertEquals("Stack redeployed", vm.state.value.notice)
        val body = sent("PUT /api/stacks/5/git/redeploy")
        assertEquals("\"refs/heads/main\"", body["RepositoryReferenceName"].toString())
        assertEquals("""[{"name":"A","value":"2"},{"name":"DB_PASSWORD","value":"hunter2"}]""", body["Env"].toString())
        assertEquals("true", body["RepositoryAuthentication"].toString())
        assertEquals("\"deploy\"", body["RepositoryUsername"].toString())
        assertEquals("\"\"", body["RepositoryPassword"].toString())
        assertEquals("true", body["Prune"].toString())
        assertEquals("true", body["PullImage"].toString())
        assertEquals("true", body["RepullImageAndRedeploy"].toString())
        assertFalse(body.keys.any { it.contains("Force") }, "CE's manual redeploy has no force option")
    }

    @Test
    fun redeployWithAnEditedRefShowsTheChange() = gitTest {
        val (_, vm) = loaded()
        vm.edit { copy(referenceName = "refs/heads/dev") }
        vm.requestRedeploy()
        val review = assertNotNull(vm.state.value.redeployReview)
        assertEquals("refs/heads/dev", review.referenceName)
        assertEquals("refs/heads/main", review.previousReferenceName)
    }

    @Test
    fun redeployWaitsForOtherUnsavedSettings() = gitTest {
        val (_, vm) = loaded()
        vm.edit { copy(tlsSkipVerify = false) }
        vm.requestRedeploy()
        assertNull(vm.state.value.redeployReview)
        assertEquals(SAVE_SETTINGS_FIRST_MESSAGE, vm.state.value.error)
    }

    @Test
    fun protectedStackBlocksRedeployAndDetach() = gitTest {
        image = "portainer/portainer-ce:2.42.0"
        val (_, vm) = loaded()
        vm.requestRedeploy()
        assertNull(vm.state.value.redeployReview)
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
        vm.clearError()
        vm.requestDetach()
        assertNull(vm.state.value.detachConfirm)
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
        assertTrue(requests.none { it.startsWith("PUT") })
    }

    @Test
    fun protectionUnknownBlocksRedeployAndDetach() = gitTest {
        fileFails = true
        val (_, vm) = loaded()
        vm.requestRedeploy()
        assertEquals(PROTECTION_UNKNOWN_MESSAGE, vm.state.value.error)
        vm.clearError()
        vm.requestDetach()
        assertEquals(PROTECTION_UNKNOWN_MESSAGE, vm.state.value.error)
        assertNull(vm.state.value.redeployReview)
        assertNull(vm.state.value.detachConfirm)
    }

    @Test
    fun protectedAtConfirmTimeStillBlocksTheRedeploy() = gitTest {
        val (_, vm) = loaded()
        vm.requestRedeploy()
        assertNotNull(vm.state.value.redeployReview)
        image = "portainer/agent:2.42.0" // the file changed meanwhile
        vm.confirmRedeploy()
        settle { !vm.state.value.checking }
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
        assertTrue(requests.none { it.startsWith("PUT") })
    }

    @Test
    fun protectedStackMayNotTurnAutomaticUpdatesOn() = gitTest {
        image = "portainer/portainer-ce:2.42.0"
        autoUpdate = "null"
        val (_, vm) = loaded()
        assertFalse(vm.state.value.form!!.autoUpdateEnabled)
        vm.setAutoUpdateEnabled(true)
        vm.requestSave()
        assertNull(vm.state.value.saveConfirm)
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
        vm.clearError()
        // Other settings may still be saved: saving deploys nothing.
        vm.setAutoUpdateEnabled(false)
        vm.edit { copy(tlsSkipVerify = false) }
        vm.requestSave()
        assertNotNull(vm.state.value.saveConfirm)
    }

    @Test
    fun detachHasItsOwnOneWayConfirmation() = gitTest {
        val (detail, vm) = loaded()
        assertTrue(vm.state.value.canDetach)
        vm.requestDetach()
        val confirm = assertNotNull(vm.state.value.detachConfirm)
        assertTrue(confirm.body.contains("can't be undone"), confirm.body)
        assertEquals(CONNECTION_DEPENDENCY_REMINDER, confirm.reminder)
        assertTrue(requests.none { it.startsWith("PUT") }, "asking sends nothing")
        vm.dismissDetach()
        assertNull(vm.state.value.detachConfirm)
        assertTrue(requests.none { it.startsWith("PUT") })

        vm.requestDetach()
        vm.confirmDetach()
        finished(vm)
        assertEquals("Stack detached from git", vm.state.value.notice)
        val body = sent("PUT /api/stacks/5")
        assertEquals("\"services:\\n  web:\\n    image: nginx\\n\"", body["StackFileContent"].toString())
        settle { detail.state.value.stack?.git == null && !detail.state.value.loading }
        settle { !vm.state.value.available }
        assertEquals(listOf(StackDetailTab.Overview, StackDetailTab.Editor), stackDetailTabs(detail.state.value))
    }

    @Test
    fun detachIsHiddenWhereTheServerLacksIt() = gitTest {
        version = "2.32.0"
        val (_, vm) = loaded()
        assertFalse(vm.state.value.canDetach)
        vm.requestDetach()
        assertNull(vm.state.value.detachConfirm)
    }

    @Test
    fun everyConfirmShowsTheConnectionReminder() = gitTest {
        val (_, vm) = loaded()
        vm.edit { copy(tlsSkipVerify = false) }
        vm.requestSave()
        assertEquals(CONNECTION_DEPENDENCY_REMINDER, vm.state.value.saveConfirm!!.reminder)
    }

    @Test
    fun unsavedChangesMakeBackAsk() = gitTest {
        val (_, vm) = loaded()
        assertTrue(vm.requestBack())
        vm.edit { copy(referenceName = "refs/heads/dev") }
        assertFalse(vm.requestBack())
        assertTrue(vm.state.value.confirmDiscard)
        vm.dismissDiscard()
        vm.edit { copy(referenceName = "refs/heads/main") }
        assertTrue(vm.requestBack())
    }

    @Test
    fun protectedStackMayNotStartPolling() = gitTest {
        image = "portainer/portainer-ce:2.42.0"
        autoUpdate = """{"Interval":"","Webhook":"05de31a2-79fa-4644-9c12-faa67e5c49f0","ForceUpdate":false,"ForcePullImage":false}"""
        val (_, vm) = loaded()
        assertEquals(AutoUpdateMechanism.Webhook, vm.state.value.form!!.mechanism)
        // Webhook -> polling while on: Portainer would start redeploying by itself.
        vm.setMechanism(AutoUpdateMechanism.Polling)
        vm.requestSave()
        assertNull(vm.state.value.saveConfirm)
        assertEquals(PROTECTED_IMAGE_MESSAGE, vm.state.value.error)
        vm.clearError()
        // Changing only the force flags of the existing webhook starts nothing new.
        vm.setMechanism(AutoUpdateMechanism.Webhook)
        vm.edit { copy(forceUpdate = true) }
        vm.requestSave()
        assertNotNull(vm.state.value.saveConfirm)
    }

    @Test
    fun protectionUnknownMayNotStartPollingEither() = gitTest {
        fileFails = true
        autoUpdate = "null"
        val (_, vm) = loaded()
        vm.setAutoUpdateEnabled(true)
        vm.requestSave()
        assertEquals(PROTECTION_UNKNOWN_MESSAGE, vm.state.value.error)
        assertNull(vm.state.value.saveConfirm)
    }

    @Test
    fun theUrlNeverReachesTheSource() = gitTest {
        version = "2.45.3"
        val (_, vm) = loaded()
        vm.edit { copy(tlsSkipVerify = false) }
        save(vm)
        assertEquals(Json.parseToJsonElement("""{"tlsSkipVerify":false}"""), sent("PUT /api/gitops/sources/7"))
    }

    @Test
    fun aStackFailureAfterTheSourceWasSavedSaysSo() = gitTest {
        version = "2.45.3"
        settingsFailEcho = true
        val (_, vm) = loaded()
        vm.edit { copy(authEnabled = false) }
        save(vm)
        val (_, message) = assertNotNull(vm.state.value.failure)
        assertTrue(message.startsWith(SOURCE_SAVED_STACK_NOT_MESSAGE), message)
        assertTrue(requests.contains("PUT /api/gitops/sources/7"))
    }

    @Test
    fun onSourcedServersAnUntouchedAuthSendsOnlyTheSourceId() = gitTest {
        for (v in listOf("2.43.0", "2.44.0")) {
            version = v
            requestLog.value = emptyList()
            val (_, vm) = loaded()
            vm.edit { copy(referenceName = "refs/heads/dev") }
            save(vm)
            val body = sent("POST /api/stacks/5/git")
            assertEquals("7", body["SourceID"].toString(), v)
            assertFalse("RepositoryAuthentication" in body, v)
            assertFalse("RepositoryPassword" in body, v)
            assertFalse("TLSSkipVerify" in body, v)
            assertTrue(requests.none { it.startsWith("PUT /api/gitops") }, v)
        }
    }

    @Test
    fun aStackWithoutASourceOnASourcedServerSavesInline() = gitTest {
        version = "2.45.3"
        hasSource = false
        val (_, vm) = loaded()
        assertNull(vm.state.value.sourceId)
        vm.edit { copy(tlsSkipVerify = false) }
        vm.requestSave()
        assertNull(vm.state.value.saveConfirm!!.sharedWarning)
        vm.confirmSave()
        finished(vm)
        val body = sent("POST /api/stacks/5/git")
        assertFalse("SourceID" in body)
        assertEquals("true", body["RepositoryAuthentication"].toString())
        assertEquals("\"\"", body["RepositoryPassword"].toString())
        assertEquals("false", body["TLSSkipVerify"].toString())
        assertTrue(requests.none { it.startsWith("PUT /api/gitops") })
    }

    @Test
    fun from244PollingOffClearsTheSourceIntervalAndSendsNoAutoUpdate() = gitTest {
        version = "2.44.0"
        val (_, vm) = loaded()
        vm.setAutoUpdateEnabled(false)
        vm.requestSave()
        assertEquals("This also changes 2 other stacks using this source.", vm.state.value.saveConfirm!!.sharedWarning)
        vm.confirmSave()
        finished(vm)
        assertEquals(Json.parseToJsonElement("""{"interval":""}"""), sent("PUT /api/gitops/sources/7"))
        assertEquals(JsonNull, sent("POST /api/stacks/5/git")["AutoUpdate"])
    }

    @Test
    fun gitStacksGetAGitTab() = gitTest {
        val (detail, _) = loaded()
        assertEquals(listOf(StackDetailTab.Overview, StackDetailTab.File, StackDetailTab.Git), stackDetailTabs(detail.state.value))
    }
}
