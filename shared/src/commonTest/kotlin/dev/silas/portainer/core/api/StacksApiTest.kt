package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.AutoUpdate
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.GitConfig
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.model.StackType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StacksApiTest {
    private val key = "test-api-key"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** Requests are appended from the engine's threads: an atomic snapshot list. */
    private val requests = MutableStateFlow(emptyList<HttpRequestData>())

    private fun client(route: (HttpRequestData) -> Pair<HttpStatusCode, String>) =
        PortainerClient("https://h", key, MockEngine { req ->
            requests.update { it + req }
            val (status, body) = route(req)
            respond(body, status, json)
        })

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String) = client { status to body }

    // Shapes copied from docs/superpowers/notes/portainer-ce-stacks-api.md (CE 2.45.3).
    private val gitStackJson = """
        {"Id":4,"Name":"web","Type":2,"EndpointId":3,"SwarmId":"","EntryPoint":"compose.yml",
         "Env":[{"name":"A","value":"1"},{"name":"B","value":"x=y"}],"ResourceControl":null,"Status":1,
         "DeploymentStatus":[{"Status":1,"Time":1700000100}],
         "ProjectPath":"/data/compose/4","CreationDate":1700000000,"CreatedBy":"admin",
         "UpdateDate":1700000500,"UpdatedBy":"bob","AdditionalFiles":["override.yml"],
         "AutoUpdate":{"Interval":"5m","Webhook":"05de31a2-79fa-4644-9c12-faa67e5c49f0","JobID":"15","ForceUpdate":true,"ForcePullImage":false},
         "Option":{"Prune":true,"HelmAtomic":false},
         "GitConfig":{"URL":"https://git.example/app.git","ReferenceName":"refs/heads/main","ConfigFilePath":"compose.yml",
           "Authentication":{"Username":"deploy","Password":""},"ConfigHash":"bc4c183d","TLSSkipVerify":true},
         "WorkflowID":2,"GitSourceId":7,"FromAppTemplate":false,"Namespace":""}
    """.trimIndent()

    private val editorStackJson = """
        {"Id":5,"Name":"db","Type":2,"EndpointId":3,"Env":null,"Status":2,"CreationDate":0,"UpdateDate":0,
         "CreatedBy":"admin","UpdatedBy":"","AdditionalFiles":null,"AutoUpdate":null,"Option":null,"GitConfig":null,
         "FromAppTemplate":true}
    """.trimIndent()

    private fun containerJson(id: String, project: String?, state: String) = """
        {"Id":"$id","Names":["/$id"],"Image":"nginx","ImageID":"sha256:${"a".repeat(64)}","State":"$state","Status":"Up",
         "Labels":{${project?.let { "\"com.docker.compose.project\":\"$it\"" } ?: ""}}}
    """.trimIndent()

    @Test
    fun listMapsManagedStacks() = runTest {
        val c = client { req ->
            if (req.url.encodedPath == "/api/stacks") HttpStatusCode.OK to "[$gitStackJson,$editorStackJson]"
            else HttpStatusCode.OK to "[]"
        }
        val stacks = c.stacks(3)
        val list = requests.value.first { it.url.encodedPath == "/api/stacks" }
        assertEquals(HttpMethod.Get, list.method)
        assertEquals("""{"EndpointID":3}""", list.url.parameters["filters"])

        val git = stacks[0]
        assertEquals(4, git.id)
        assertEquals("web", git.name)
        assertEquals(StackType.Compose, git.type)
        assertEquals(3, git.endpointId)
        assertEquals(StackStatus.Active, git.status)
        assertEquals(StackControl.Full, git.control)
        assertEquals(
            GitConfig(
                url = "https://git.example/app.git",
                referenceName = "refs/heads/main",
                configFilePath = "compose.yml",
                additionalFiles = listOf("override.yml"),
                authenticated = true,
                username = "deploy",
                tlsSkipVerify = true,
                configHash = "bc4c183d",
                sourceId = 7,
            ),
            git.git,
        )
        assertEquals(AutoUpdate("5m", "05de31a2-79fa-4644-9c12-faa67e5c49f0", forcePullImage = false, forceUpdate = true), git.autoUpdate)
        assertEquals(listOf(EnvVar("A", "1"), EnvVar("B", "x=y")), git.env)
        assertEquals(1700000000L, git.createdAt)
        assertEquals(1700000500L, git.updatedAt)
        assertEquals("bob", git.updatedBy)
        assertFalse(git.fromTemplate)
        assertTrue(git.prune)

        val editor = stacks[1]
        assertEquals(StackStatus.Inactive, editor.status)
        assertNull(editor.git)
        assertNull(editor.autoUpdate)
        assertEquals(emptyList(), editor.env)
        assertNull(editor.createdAt)
        assertNull(editor.updatedAt)
        assertNull(editor.updatedBy)
        assertTrue(editor.fromTemplate)
        assertFalse(editor.prune)
    }

    @Test
    fun missingKeysUseDefaults() = runTest {
        val stack = client(body = """{"Id":9,"Name":"min","Type":2,"EndpointId":1}""").stack(9)
        assertEquals(9, stack.id)
        assertEquals(StackStatus.Inactive, stack.status)
        assertNull(stack.git)
        assertNull(stack.autoUpdate)
        assertEquals(emptyList(), stack.env)
    }

    @Test
    fun autoUpdateWithoutIntervalOrWebhookIsOff() = runTest {
        val s = client(body = """{"Id":1,"Name":"a","Type":2,"EndpointId":1,"Status":1,
            "AutoUpdate":{"Interval":"","Webhook":"","ForceUpdate":false,"ForcePullImage":false}}""").stack(1)
        assertNull(s.autoUpdate)
    }

    @Test
    fun gitWithoutAuthenticationIsNotAuthenticated() = runTest {
        val s = client(body = """{"Id":1,"Name":"a","Type":2,"EndpointId":1,"Status":1,
            "GitConfig":{"URL":"https://g/x.git","ReferenceName":"refs/heads/main","ConfigFilePath":"docker-compose.yml","ConfigHash":"","TLSSkipVerify":false}}""").stack(1)
        val git = s.git!!
        assertFalse(git.authenticated)
        assertNull(git.username)
        assertNull(git.configHash)
        assertEquals(emptyList(), git.additionalFiles)
        assertNull(git.sourceId)
    }

    @Test
    fun deployingAndErrorStatusesMap() = runTest {
        val deploying = client(body = """{"Id":1,"Name":"a","Type":2,"EndpointId":1,"Status":3}""").stack(1)
        assertEquals(StackStatus.Deploying, deploying.status)
        val failed = client(body = """{"Id":1,"Name":"a","Type":2,"EndpointId":1,"Status":4,
            "DeploymentStatus":[{"Status":3,"Time":1},{"Status":4,"Time":2,"Message":"pull access denied"}]}""").stack(1)
        assertEquals(StackStatus.Error, failed.status)
        assertEquals("pull access denied", failed.statusMessage)
    }

    @Test
    fun listKeepsOnlyComposeStacksOfTheEndpoint() = runTest {
        val c = client { req ->
            if (req.url.encodedPath == "/api/stacks") HttpStatusCode.OK to """[
                {"Id":1,"Name":"a","Type":2,"EndpointId":3,"Status":1},
                {"Id":2,"Name":"swarm","Type":1,"EndpointId":3,"Status":1},
                {"Id":3,"Name":"other","Type":2,"EndpointId":9,"Status":1}]"""
            else HttpStatusCode.OK to "[]"
        }
        assertEquals(listOf("a"), c.stacks(3).map { it.name })
    }

    @Test
    fun listAddsExternalStacksFromContainerLabels() = runTest {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/api/stacks" -> HttpStatusCode.OK to """[{"Id":1,"Name":"web","Type":2,"EndpointId":3,"Status":1}]"""
                "/api/endpoints/3/docker/containers/json" -> HttpStatusCode.OK to "[" + listOf(
                    containerJson("w1", "web", "running"),
                    containerJson("e1", "ext", "exited"),
                    containerJson("e2", "ext", "running"),
                    containerJson("s1", "stopped", "exited"),
                    containerJson("solo", null, "running"),
                ).joinToString(",") + "]"
                else -> HttpStatusCode.NotFound to "{}"
            }
        }
        val stacks = c.stacks(3)
        assertEquals(listOf("web", "ext", "stopped"), stacks.map { it.name })
        val containersReq = requests.value.first { it.url.encodedPath.endsWith("/containers/json") }
        assertEquals("1", containersReq.url.parameters["all"])

        assertEquals(StackControl.Full, stacks[0].control)
        val ext = stacks[1]
        assertNull(ext.id)
        assertEquals(StackControl.Limited, ext.control)
        assertEquals(StackType.Compose, ext.type)
        assertEquals(3, ext.endpointId)
        assertEquals(StackStatus.Active, ext.status)
        assertNull(ext.git)
        assertNull(ext.autoUpdate)
        assertEquals(StackStatus.Inactive, stacks[2].status)
    }

    @Test
    fun emptyListResponseIsEmpty() = runTest {
        val c = client { req ->
            if (req.url.encodedPath == "/api/stacks") HttpStatusCode.NoContent to "" else HttpStatusCode.OK to "[]"
        }
        assertEquals(emptyList(), c.stacks(1))
    }

    @Test
    fun stackInspectsById() = runTest {
        val s = client(body = gitStackJson).stack(4)
        assertEquals("/api/stacks/4", requests.value.single().url.encodedPath)
        assertEquals(HttpMethod.Get, requests.value.single().method)
        assertEquals("web", s.name)
    }

    @Test
    fun stackFileReturnsContent() = runTest {
        val file = client(body = """{"StackFileContent":"services:\n  web:\n    image: nginx\n"}""").stackFile(4)
        assertEquals("services:\n  web:\n    image: nginx\n", file)
        assertEquals("/api/stacks/4/file", requests.value.single().url.encodedPath)
    }

    @Test
    fun startAndStopPostWithEndpointId() = runTest {
        val c = client(body = editorStackJson)
        c.startStack(5, 3)
        c.stopStack(5, 3)
        val (start, stop) = requests.value
        assertEquals(HttpMethod.Post, start.method)
        assertEquals("/api/stacks/5/start", start.url.encodedPath)
        assertEquals("3", start.url.parameters["endpointId"])
        assertEquals(HttpMethod.Post, stop.method)
        assertEquals("/api/stacks/5/stop", stop.url.encodedPath)
        assertEquals("3", stop.url.parameters["endpointId"])
    }

    @Test
    fun deleteSendsOnlyEndpointId() = runTest {
        client(HttpStatusCode.NoContent, "").deleteStack(5, 3)
        val r = requests.value.single()
        assertEquals(HttpMethod.Delete, r.method)
        assertEquals("/api/stacks/5", r.url.encodedPath)
        assertEquals(setOf("endpointId"), r.url.parameters.names())
        assertEquals("3", r.url.parameters["endpointId"])
    }

    @Test
    fun startConflictMapsToApiErrorWithRedactedKey() = runTest {
        val e = assertFailsWith<PortainerException.Api> {
            client(HttpStatusCode.Conflict, """{"message":"Unable to start stack","details":"Stack is already active $key"}""").startStack(1, 1)
        }
        assertEquals(409, e.status)
        assertFalse(e.message!!.contains(key))
        assertTrue(e.message!!.contains("***"))
    }

    @Test
    fun failuresNeverLeakTheKey() = runTest {
        val cases = listOf(
            HttpStatusCode.InternalServerError to """{"message":"boom $key"}""",
            HttpStatusCode.NotFound to """{"message":"missing $key"}""",
            HttpStatusCode.Forbidden to """{"message":"no $key"}""",
        )
        for ((status, body) in cases) {
            val calls: List<suspend PortainerClient.() -> Unit> = listOf(
                { stacks(1) }, { stack(1) }, { stackFile(1) }, { startStack(1, 1) }, { stopStack(1, 1) }, { deleteStack(1, 1) },
            )
            for (call in calls) {
                val e = assertFailsWith<PortainerException> { client(status, body).call() }
                assertFalse(e.message!!.contains(key), "leaked key: ${e.message}")
            }
        }
        assertFailsWith<PortainerException.NotFound> { client(HttpStatusCode.NotFound, "{}").stack(1) }
        assertFailsWith<PortainerException.Unauthorized> { client(HttpStatusCode.Unauthorized, "{}").stackFile(1) }
    }

    @Test
    fun unexpectedBodyIsApiError() = runTest {
        assertFailsWith<PortainerException.Api> { client(body = "<html>login</html>").stack(1) }
    }
}
