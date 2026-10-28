package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.AutoUpdate
import dev.silas.portainer.core.model.EnvVar
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Git settings, redeploy and detach. Shapes and keep-rules: docs/superpowers/notes/portainer-ce-stacks-api.md. */
class StacksGitApiTest {
    private val key = "test-api-key"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = MutableStateFlow(emptyList<HttpRequestData>())

    private fun gitStack(id: Int, sourceId: Int = 7, git: Boolean = true) = """
        {"Id":$id,"Name":"s$id","Type":2,"EndpointId":3,"Status":1,"Env":[{"name":"A","value":"1"}],
         ${if (git) """"GitConfig":{"URL":"https://git.example/app.git","ReferenceName":"refs/heads/main","ConfigFilePath":"compose.yml"},""" else ""}
         "GitSourceId":$sourceId}
    """.trimIndent()

    private fun client(version: String, route: (HttpRequestData) -> Pair<HttpStatusCode, String>? = { null }) =
        PortainerClient("https://h", key, MockEngine { req ->
            requests.update { it + req }
            val (status, body) = route(req) ?: when (req.url.encodedPath) {
                "/api/status" -> HttpStatusCode.OK to """{"Version":"$version"}"""
                "/api/stacks/4/file" -> HttpStatusCode.OK to """{"StackFileContent":"services: {}\n"}"""
                "/api/stacks/4" -> HttpStatusCode.OK to gitStack(4)
                else -> HttpStatusCode.OK to gitStack(4)
            }
            respond(body, status, json)
        })

    private fun sent(method: HttpMethod, path: String): Pair<HttpRequestData, JsonObject> {
        val r = requests.value.single { it.method == method && it.url.encodedPath == path }
        return r to Json.parseToJsonElement((r.body as TextContent).text).jsonObject
    }

    private val webhook = AutoUpdate(interval = null, webhookId = "05de31a2-79fa-4644-9c12-faa67e5c49f0", forcePullImage = true, forceUpdate = false)

    private fun settings(
        auth: GitAuth? = GitAuth("deploy", null),
        autoUpdate: AutoUpdate? = webhook,
        env: List<EnvVar> = listOf(EnvVar("A", "1"), EnvVar("B", "x=y")),
        sourceId: Int? = null,
        source: GitSourceUpdate? = null,
    ) = GitSettingsRequest(
        referenceName = "refs/heads/main",
        configFilePath = "compose.yml",
        additionalFiles = listOf("override.yml"),
        auth = auth,
        tlsSkipVerify = true,
        autoUpdate = autoUpdate,
        env = env,
        prune = true,
        sourceId = sourceId,
        source = source,
    )

    @Test
    fun settingsOn245WithASourceSendRefPathsAutoUpdateEnvAndSourceIdButNoInlineCredentials() = runTest {
        client("2.45.3").updateGitSettings(4, 3, settings(sourceId = 7))
        val (r, body) = sent(HttpMethod.Post, "/api/stacks/4/git")
        assertEquals(setOf("endpointId"), r.url.parameters.names())
        assertEquals("3", r.url.parameters["endpointId"])
        assertEquals(
            Json.parseToJsonElement(
                """{"RepositoryReferenceName":"refs/heads/main","ConfigFilePath":"compose.yml","AdditionalFiles":["override.yml"],
                "AutoUpdate":{"Interval":"","Webhook":"05de31a2-79fa-4644-9c12-faa67e5c49f0","ForceUpdate":false,"ForcePullImage":true},
                "Env":[{"name":"A","value":"1"},{"name":"B","value":"x=y"}],"Prune":true,"SourceID":7}""",
            ),
            body,
        )
        assertTrue(requests.value.none { it.url.encodedPath.startsWith("/api/gitops") }, "nothing changed on the source")
    }

    @Test
    fun keepStoredPasswordIsAuthOnWithAnEmptyPasswordAndCurrentTls() = runTest {
        client("2.39.8").updateGitSettings(4, 3, settings())
        val (_, body) = sent(HttpMethod.Post, "/api/stacks/4/git")
        assertEquals("true", body["RepositoryAuthentication"].toString())
        assertEquals("\"deploy\"", body["RepositoryUsername"].toString())
        assertEquals("\"\"", body["RepositoryPassword"].toString(), "empty = Portainer keeps the stored password")
        assertEquals("true", body["TLSSkipVerify"].toString())
        // 2.39 has neither the compose paths nor sources in this payload, nor the old auth type.
        assertFalse("ConfigFilePath" in body)
        assertFalse("AdditionalFiles" in body)
        assertFalse("SourceID" in body)
        assertFalse("RepositoryAuthorizationType" in body)
        assertFalse("RepositoryURL" in body)
        assertEquals("\"refs/heads/main\"", body["RepositoryReferenceName"].toString())
        assertEquals(2, (body["Env"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun aNewPasswordIsSentAndAuthOffSendsTheNoAuthForm() = runTest {
        client("2.42.0").updateGitSettings(4, 3, settings(auth = GitAuth("deploy", "s3cret")))
        val (_, withPassword) = sent(HttpMethod.Post, "/api/stacks/4/git")
        assertEquals("\"s3cret\"", withPassword["RepositoryPassword"].toString())
        assertEquals("[\"override.yml\"]", withPassword["AdditionalFiles"].toString(), "2.40+ has the paths")

        requests.value = emptyList()
        client("2.42.0").updateGitSettings(4, 3, settings(auth = null))
        val (_, off) = sent(HttpMethod.Post, "/api/stacks/4/git")
        assertEquals("false", off["RepositoryAuthentication"].toString())
        assertEquals("\"\"", off["RepositoryUsername"].toString())
        assertEquals("\"\"", off["RepositoryPassword"].toString())
    }

    @Test
    fun autoUpdateOffIsAnExplicitNullAndEmptyEnvAnExplicitList() = runTest {
        client("2.45.3").updateGitSettings(4, 3, settings(autoUpdate = null, env = emptyList(), sourceId = 7))
        val (_, body) = sent(HttpMethod.Post, "/api/stacks/4/git")
        assertEquals(JsonNull, body["AutoUpdate"])
        assertEquals("[]", body["Env"].toString())
    }

    @Test
    fun pollingSendsTheIntervalOnTheStackOnEveryVersion() = runTest {
        val polling = AutoUpdate(interval = "5m", webhookId = null, forcePullImage = false, forceUpdate = true)
        client("2.43.0").updateGitSettings(4, 3, settings(autoUpdate = polling))
        val (_, body) = sent(HttpMethod.Post, "/api/stacks/4/git")
        assertEquals(
            Json.parseToJsonElement("""{"Interval":"5m","Webhook":"","ForceUpdate":true,"ForcePullImage":false}"""),
            body["AutoUpdate"],
        )
    }

    @Test
    fun sourceChangesGoToTheGitOpsSourceFirst() = runTest {
        val source = GitSourceUpdate(tlsSkipVerify = false, auth = GitSourceAuth.Basic(GitAuth("bob", null)), interval = "10m")
        client("2.45.3").updateGitSettings(4, 3, settings(sourceId = 7, source = source))
        val paths = requests.value.filter { it.method != HttpMethod.Get }.map { "${it.method.value} ${it.url.encodedPath}" }
        assertEquals(listOf("PUT /api/gitops/sources/7", "POST /api/stacks/4/git"), paths)
        val (_, body) = sent(HttpMethod.Put, "/api/gitops/sources/7")
        assertEquals(
            Json.parseToJsonElement("""{"tlsSkipVerify":false,"authentication":{"username":"bob"},"interval":"10m"}"""),
            body,
            "no password key: the source keeps its stored password",
        )
    }

    @Test
    fun aStackFailureAfterTheSourceWasSavedSaysSo() = runTest {
        val failing = { req: HttpRequestData ->
            if (req.url.encodedPath == "/api/stacks/4/git") HttpStatusCode.InternalServerError to """{"message":"repository not found $key"}""" else null
        }
        val e = assertFailsWith<IllegalStateException> {
            client("2.45.3", failing).updateGitSettings(4, 3, settings(sourceId = 7, source = GitSourceUpdate(tlsSkipVerify = false)))
        }
        assertTrue(e.message!!.startsWith(SOURCE_SAVED_STACK_NOT_MESSAGE), e.message)
        assertTrue(e.message!!.contains("repository not found"), e.message)
        assertFalse(e.message!!.contains(key))
        // Without a source change the stack's own error is passed on as it is.
        requests.value = emptyList()
        assertFailsWith<PortainerException.Api> { client("2.45.3", failing).updateGitSettings(4, 3, settings(sourceId = 7)) }
        assertTrue(requests.value.none { it.url.encodedPath.startsWith("/api/gitops") })
    }

    @Test
    fun sourceAuthOffIsAnEmptyAuthenticationAndUnchangedFieldsAreLeftOut() = runTest {
        client("2.45.3").updateGitSettings(4, 3, settings(sourceId = 7, source = GitSourceUpdate(auth = GitSourceAuth.None)))
        assertEquals(Json.parseToJsonElement("""{"authentication":{}}"""), sent(HttpMethod.Put, "/api/gitops/sources/7").second)

        requests.value = emptyList()
        client("2.45.3").updateGitSettings(4, 3, settings(sourceId = 7, source = GitSourceUpdate(auth = GitSourceAuth.Basic(GitAuth("bob", "t0ken")))))
        assertEquals(
            Json.parseToJsonElement("""{"authentication":{"username":"bob","password":"t0ken"}}"""),
            sent(HttpMethod.Put, "/api/gitops/sources/7").second,
        )
    }

    @Test
    fun sourceIntervalIsOnlySentFrom244() = runTest {
        client("2.43.0").updateGitSettings(4, 3, settings(sourceId = 7, source = GitSourceUpdate(tlsSkipVerify = true, interval = "5m")))
        assertEquals(Json.parseToJsonElement("""{"tlsSkipVerify":true}"""), sent(HttpMethod.Put, "/api/gitops/sources/7").second)
    }

    @Test
    fun sourcesAreRefusedBefore243() = runTest {
        assertFailsWith<IllegalStateException> {
            client("2.42.0").updateGitSettings(4, 3, settings(sourceId = 7, source = GitSourceUpdate(tlsSkipVerify = true)))
        }
        assertTrue(requests.value.none { it.method != HttpMethod.Get })
        // A source id alone (no source changes) is simply not sent to an older server.
        client("2.42.0").updateGitSettings(4, 3, settings(sourceId = 7))
        assertFalse("SourceID" in sent(HttpMethod.Post, "/api/stacks/4/git").second)
    }

    @Test
    fun redeploySendsCurrentRefEnvPruneAndPullWithStoredCredentials() = runTest {
        val r = GitRedeployRequest(
            referenceName = "refs/heads/main",
            auth = GitAuth("deploy", null),
            env = listOf(EnvVar("A", "1")),
            pullImage = true,
            prune = true,
        )
        for (version in listOf("2.39.8", "2.45.3")) {
            requests.value = emptyList()
            client(version).gitRedeploy(4, 3, r)
            val (req, body) = sent(HttpMethod.Put, "/api/stacks/4/git/redeploy")
            assertEquals("3", req.url.parameters["endpointId"], version)
            assertEquals(
                Json.parseToJsonElement(
                    """{"RepositoryReferenceName":"refs/heads/main","RepositoryAuthentication":true,"RepositoryUsername":"deploy",
                    "RepositoryPassword":"","Env":[{"name":"A","value":"1"}],"Prune":true,"RepullImageAndRedeploy":true,"PullImage":true}""",
                ),
                body,
                version,
            )
        }
        requests.value = emptyList()
        client("2.45.3").gitRedeploy(4, 3, r.copy(auth = null, env = emptyList(), pullImage = false, prune = false))
        val (_, body) = sent(HttpMethod.Put, "/api/stacks/4/git/redeploy")
        assertEquals("false", body["RepositoryAuthentication"].toString())
        assertEquals("[]", body["Env"].toString(), "an empty env is sent as a list, never left out")
        assertEquals("false", body["Prune"].toString())
        assertEquals("\"refs/heads/main\"", body["RepositoryReferenceName"].toString())
    }

    @Test
    fun detachPutsTheCurrentFileAndEnvAsAWebEditorStack() = runTest {
        client("2.45.3").detachFromGit(4, 3)
        val (req, body) = sent(HttpMethod.Put, "/api/stacks/4")
        assertEquals("3", req.url.parameters["endpointId"])
        assertEquals(
            Json.parseToJsonElement("""{"StackFileContent":"services: {}\n","Env":[{"name":"A","value":"1"}],"RepullImageAndRedeploy":false,"PullImage":false}"""),
            body,
        )
    }

    @Test
    fun detachKeepsTheStacksPruneSettingWhereTheUpdateHasIt() = runTest {
        val pruning = gitStack(4).replace("\"GitSourceId\"", "\"Option\":{\"Prune\":true},\"GitSourceId\"")
        client("2.45.3") { if (it.url.encodedPath == "/api/stacks/4" && it.method == HttpMethod.Get) HttpStatusCode.OK to pruning else null }
            .detachFromGit(4, 3)
        assertEquals("true", sent(HttpMethod.Put, "/api/stacks/4").second["Prune"].toString())
        // Before 2.40 the update has no Prune: nothing is sent.
        requests.value = emptyList()
        client("2.39.8") { if (it.url.encodedPath == "/api/stacks/4" && it.method == HttpMethod.Get) HttpStatusCode.OK to pruning else null }
            .detachFromGit(4, 3)
        assertFalse("Prune" in sent(HttpMethod.Put, "/api/stacks/4").second)
    }

    @Test
    fun detachIsGatedAndOnlyForGitStacks() = runTest {
        assertFailsWith<IllegalStateException> { client("2.32.0").detachFromGit(4, 3) }
        assertFailsWith<IllegalStateException> {
            client("2.45.3") { if (it.url.encodedPath == "/api/stacks/4") HttpStatusCode.OK to gitStack(4, git = false) else null }.detachFromGit(4, 3)
        }
        assertTrue(requests.value.none { it.method == HttpMethod.Put })
    }

    @Test
    fun stacksUsingASourceAreCountedFromEveryGitStack() = runTest {
        val c = client("2.45.3") { req ->
            when (req.url.encodedPath) {
                "/api/stacks" -> HttpStatusCode.OK to "[${gitStack(4)},${gitStack(5)},${gitStack(6, sourceId = 8)},${gitStack(9, git = false)},${gitStack(10)}]"
                "/api/stacks/5" -> HttpStatusCode.OK to gitStack(5)
                "/api/stacks/6" -> HttpStatusCode.OK to gitStack(6, sourceId = 8)
                "/api/stacks/10" -> HttpStatusCode.OK to gitStack(10)
                else -> null
            }
        }
        assertEquals(2, c.stacksUsingGitSource(sourceId = 7, exceptStackId = 4))
        val list = requests.value.first { it.url.encodedPath == "/api/stacks" }
        assertTrue(list.url.parameters.isEmpty(), "every endpoint: a source can be shared across environments")
        assertFalse(requests.value.any { it.url.encodedPath == "/api/stacks/9" }, "web-editor stacks have no source")
    }

    @Test
    fun secretsNeverReachToString() {
        val auth = GitAuth("deploy", "s3cret")
        assertFalse(auth.toString().contains("s3cret"))
        val s = settings(auth = auth, source = GitSourceUpdate(auth = GitSourceAuth.Basic(auth)), env = listOf(EnvVar("DB_PASSWORD", "hunter2")))
        assertFalse(s.toString().contains("s3cret"))
        assertFalse(s.toString().contains("hunter2"))
        val r = GitRedeployRequest("refs/heads/main", auth, listOf(EnvVar("DB_PASSWORD", "hunter2")), pullImage = false, prune = false)
        assertFalse(r.toString().contains("s3cret"))
        assertFalse(r.toString().contains("hunter2"))
    }
}
