package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.AutoUpdate
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.StackStatus
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stack creation and custom templates. Shapes: docs/superpowers/notes/portainer-ce-stacks-api.md, "Create" and "Custom templates". */
class StacksCreateApiTest {
    private val key = "test-api-key"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = MutableStateFlow(emptyList<HttpRequestData>())

    private val created = """{"Id":12,"Name":"web","Type":2,"EndpointId":3,"Status":3,"Env":[{"name":"A","value":"1"}]}"""

    private fun client(version: String = "2.45.3", route: (HttpRequestData) -> Pair<HttpStatusCode, String>? = { null }) =
        PortainerClient("https://h", key, MockEngine { req ->
            requests.update { it + req }
            val (status, body) = route(req) ?: when (req.url.encodedPath) {
                "/api/status" -> HttpStatusCode.OK to """{"Version":"$version"}"""
                else -> HttpStatusCode.OK to created
            }
            respond(body, status, json)
        })

    private fun sent(method: HttpMethod, path: String): Pair<HttpRequestData, JsonObject> {
        val r = requests.value.single { it.method == method && it.url.encodedPath == path }
        return r to Json.parseToJsonElement((r.body as TextContent).text).jsonObject
    }

    private val webhook = AutoUpdate(interval = null, webhookId = "05de31a2-79fa-4644-9c12-faa67e5c49f0", forcePullImage = true, forceUpdate = false)

    private fun git(
        auth: GitAuth? = GitAuth("deploy", "s3cret"),
        autoUpdate: AutoUpdate? = webhook,
        configFilePath: String = "compose.yml",
        sourceId: Int? = null,
    ) = GitSettingsRequest(
        referenceName = "refs/heads/main",
        configFilePath = configFilePath,
        additionalFiles = listOf("override.yml"),
        auth = auth,
        tlsSkipVerify = true,
        autoUpdate = autoUpdate,
        env = listOf(EnvVar("A", "1"), EnvVar("B", "x=y")),
        prune = false,
        sourceId = sourceId,
    )

    @Test
    fun createFromTextPostsNameFileAndEnvToTheStringEndpoint() = runTest {
        val stack = client().createStackFromText(3, "web", "services:\n  web:\n    image: nginx\n", listOf(EnvVar("A", "1"), EnvVar("B", "")))
        val (r, body) = sent(HttpMethod.Post, "/api/stacks/create/standalone/string")
        assertEquals("3", r.url.parameters["endpointId"])
        assertEquals(
            Json.parseToJsonElement(
                """{"Name":"web","StackFileContent":"services:\n  web:\n    image: nginx\n",
                "Env":[{"name":"A","value":"1"},{"name":"B","value":""}],"FromAppTemplate":false}""",
            ),
            body,
        )
        assertEquals(12, stack.id)
        assertEquals("web", stack.name)
        assertEquals(StackStatus.Deploying, stack.status)
    }

    @Test
    fun createFromTextErrorsAreRedactedPortainerExceptions() = runTest {
        val c = client { if (it.url.encodedPath.startsWith("/api/stacks/create")) HttpStatusCode.Conflict to """{"message":"A stack with the name 'web' already exists $key"}""" else null }
        val e = assertFailsWith<PortainerException> { c.createStackFromText(3, "web", "services: {}\n", emptyList()) }
        assertFalse(e.message.orEmpty().contains(key))
    }

    @Test
    fun createFromRepositoryOn245SendsInlineGitSettingsWithoutSourceIdWhenNoneIsChosen() = runTest {
        val stack = client().createStackFromRepository(3, "web", "https://git.example/app.git", git())
        val (r, body) = sent(HttpMethod.Post, "/api/stacks/create/standalone/repository")
        assertEquals("3", r.url.parameters["endpointId"])
        assertEquals(
            Json.parseToJsonElement(
                """{"Name":"web","RepositoryURL":"https://git.example/app.git","RepositoryReferenceName":"refs/heads/main",
                "RepositoryAuthentication":true,"RepositoryUsername":"deploy","RepositoryPassword":"s3cret",
                "ComposeFile":"compose.yml","AdditionalFiles":["override.yml"],
                "AutoUpdate":{"Interval":"","Webhook":"05de31a2-79fa-4644-9c12-faa67e5c49f0","ForceUpdate":false,"ForcePullImage":true},
                "Env":[{"name":"A","value":"1"},{"name":"B","value":"x=y"}],"FromAppTemplate":false,"TLSSkipVerify":true}""",
            ),
            body,
        )
        assertEquals(12, stack.id)
    }

    @Test
    fun createFromRepositoryWithoutAuthSendsAuthOffAndDefaultsTheComposePath() = runTest {
        client().createStackFromRepository(3, "web", "https://git.example/app.git", git(auth = null, autoUpdate = null, configFilePath = " "))
        val (_, body) = sent(HttpMethod.Post, "/api/stacks/create/standalone/repository")
        assertEquals("false", body["RepositoryAuthentication"].toString())
        assertEquals("\"\"", body["RepositoryPassword"].toString())
        assertEquals("\"docker-compose.yml\"", body["ComposeFile"].toString())
        assertEquals("null", body["AutoUpdate"].toString())
    }

    @Test
    fun createFromRepositoryNeedsAPasswordWhenAuthIsOn() = runTest {
        for (password in listOf(null, "")) {
            assertFailsWith<IllegalStateException> {
                client().createStackFromRepository(3, "web", "https://git.example/app.git", git(auth = GitAuth("deploy", password)))
            }
        }
        assertTrue(requests.value.none { it.url.encodedPath.startsWith("/api/stacks/create") })
    }

    @Test
    fun createFromRepositorySendsSourceIdOnlyFrom243() = runTest {
        client("2.43.0").createStackFromRepository(3, "web", "https://git.example/app.git", git(sourceId = 7))
        assertEquals("7", sent(HttpMethod.Post, "/api/stacks/create/standalone/repository").second["SourceID"].toString())
        requests.value = emptyList()
        assertFailsWith<IllegalStateException> {
            client("2.42.0").createStackFromRepository(3, "web", "https://git.example/app.git", git(sourceId = 7))
        }
        assertTrue(requests.value.none { it.url.encodedPath.startsWith("/api/stacks/create") })
    }

    @Test
    fun pollingIntervalIsRefusedWhereTheServerWouldIgnoreIt() = runTest {
        val polling = AutoUpdate(interval = "5m", webhookId = null, forcePullImage = false, forceUpdate = false)
        assertFailsWith<IllegalStateException> {
            client("2.44.0").createStackFromRepository(3, "web", "https://git.example/app.git", git(autoUpdate = polling))
        }
        assertTrue(requests.value.none { it.url.encodedPath.startsWith("/api/stacks/create") })
        client("2.43.0").createStackFromRepository(3, "web", "https://git.example/app.git", git(autoUpdate = polling))
        val body = sent(HttpMethod.Post, "/api/stacks/create/standalone/repository").second
        assertEquals("\"5m\"", body["AutoUpdate"]!!.jsonObject["Interval"].toString())
    }

    @Test
    fun customTemplatesListOnlyDockerStandaloneComposeOnes() = runTest {
        val list = """[
            {"Id":1,"Title":"Web","Description":"nginx","Type":2,"EdgeTemplate":false,
             "Variables":[{"name":"PORT","label":"Port","defaultValue":"8080","description":"x"}]},
            {"Id":2,"Title":"Swarm","Description":"","Type":1,"EdgeTemplate":false},
            {"Id":3,"Title":"Edge","Description":"","Type":2,"EdgeTemplate":true},
            {"Id":4,"Title":"K8s","Description":"","Type":3},
            {"Id":5,"Title":"Plain","Description":"","Type":2,"Variables":null},
            {"Id":6,"Title":"FromGit","Description":"","Type":2,"GitConfig":{"URL":"https://git.example/t.git"}}
        ]"""
        val c = client { if (it.url.encodedPath == "/api/custom_templates") HttpStatusCode.OK to list else null }
        val templates = c.customTemplates()
        assertEquals(
            listOf(
                CustomTemplate(1, "Web", "nginx", listOf(TemplateVariable("PORT", "Port", "8080"))),
                CustomTemplate(5, "Plain", "", emptyList()),
                CustomTemplate(6, "FromGit", "", emptyList(), gitBacked = true),
            ),
            templates,
        )
        val r = requests.value.single { it.url.encodedPath == "/api/custom_templates" }
        assertEquals(HttpMethod.Get, r.method)
        assertEquals(listOf("2"), r.url.parameters.getAll("type"))
        assertEquals("false", r.url.parameters["edge"])
    }

    @Test
    fun customTemplateFileReadsFileContent() = runTest {
        val c = client { if (it.url.encodedPath == "/api/custom_templates/1/file") HttpStatusCode.OK to """{"FileContent":"services: {}\n"}""" else null }
        assertEquals("services: {}\n", c.customTemplateFile(1))
    }

    @Test
    fun gitBackedTemplateFileIsFetchedFromTheRepositoryAsCeDoes() = runTest {
        val c = client { if (it.url.encodedPath == "/api/custom_templates/6/git_fetch") HttpStatusCode.OK to """{"FileContent":"services: {}\n"}""" else null }
        assertEquals("services: {}\n", c.customTemplateFile(6, git = true))
        val r = requests.value.single()
        assertEquals(HttpMethod.Put, r.method)
        assertEquals("/api/custom_templates/6/git_fetch", r.url.encodedPath)
    }

    @Test
    fun templateCreatesSendFromAppTemplate() = runTest {
        client().createStackFromText(3, "web", "services: {}\n", emptyList(), fromAppTemplate = true)
        assertEquals("true", sent(HttpMethod.Post, "/api/stacks/create/standalone/string").second["FromAppTemplate"].toString())
    }
}
