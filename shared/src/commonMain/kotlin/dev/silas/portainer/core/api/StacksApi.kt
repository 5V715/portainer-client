package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.AutoUpdate
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.GitConfig
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.model.StackType
import dev.silas.portainer.core.stacks.stackFeatures
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Field names: docs/superpowers/notes/portainer-ce-stacks-api.md (verified against CE 2.45.3).

/** Compose up/down runs inside these requests (image pulls included), far beyond the client default timeout. */
private const val STACK_OPERATION_TIMEOUT_MS = 10 * 60_000L

private const val COMPOSE_STACK_TYPE = 2

@Serializable
private data class PairDto(
    @SerialName("name") val name: String = "",
    @SerialName("value") val value: String = "",
)

@Serializable
private data class GitAuthenticationDto(@SerialName("Username") val username: String = "")

@Serializable
private data class GitConfigDto(
    @SerialName("URL") val url: String = "",
    @SerialName("ReferenceName") val referenceName: String = "",
    @SerialName("ConfigFilePath") val configFilePath: String = "",
    @SerialName("Authentication") val authentication: GitAuthenticationDto? = null,
    @SerialName("ConfigHash") val configHash: String = "",
    @SerialName("TLSSkipVerify") val tlsSkipVerify: Boolean = false,
)

@Serializable
private data class AutoUpdateDto(
    @SerialName("Interval") val interval: String = "",
    @SerialName("Webhook") val webhook: String = "",
    @SerialName("ForceUpdate") val forceUpdate: Boolean = false,
    @SerialName("ForcePullImage") val forcePullImage: Boolean = false,
)

@Serializable
private data class StackOptionDto(@SerialName("Prune") val prune: Boolean = false)

@Serializable
private data class DeploymentStatusDto(
    @SerialName("Status") val status: Int = 0,
    @SerialName("Message") val message: String = "",
)

@Serializable
private data class StackDto(
    @SerialName("Id") val id: Int = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: Int = 0,
    @SerialName("EndpointId") val endpointId: Int = 0,
    @SerialName("Status") val status: Int = 0,
    @SerialName("DeploymentStatus") val deploymentStatus: List<DeploymentStatusDto>? = null,
    @SerialName("Env") val env: List<PairDto>? = null,
    @SerialName("CreationDate") val creationDate: Long = 0,
    @SerialName("UpdateDate") val updateDate: Long = 0,
    @SerialName("UpdatedBy") val updatedBy: String = "",
    @SerialName("AdditionalFiles") val additionalFiles: List<String>? = null,
    @SerialName("AutoUpdate") val autoUpdate: AutoUpdateDto? = null,
    @SerialName("Option") val option: StackOptionDto? = null,
    @SerialName("GitConfig") val gitConfig: GitConfigDto? = null,
    @SerialName("GitSourceId") val gitSourceId: Int = 0,
    @SerialName("FromAppTemplate") val fromAppTemplate: Boolean = false,
) {
    fun toModel(): Stack {
        val status = StackStatus.fromCode(status)
        return Stack(
            id = id,
            name = name,
            type = StackType.fromCode(type),
            endpointId = endpointId,
            status = status,
            control = StackControl.Full,
            git = gitConfig?.let { g ->
                GitConfig(
                    url = g.url,
                    referenceName = g.referenceName,
                    configFilePath = g.configFilePath,
                    additionalFiles = additionalFiles.orEmpty(),
                    authenticated = g.authentication != null,
                    username = g.authentication?.username?.takeIf { it.isNotEmpty() },
                    tlsSkipVerify = g.tlsSkipVerify,
                    configHash = g.configHash.takeIf { it.isNotEmpty() },
                    sourceId = gitSourceId.takeIf { it != 0 },
                )
            },
            // Portainer's own UI treats an AutoUpdate without interval and webhook as "off".
            autoUpdate = autoUpdate?.takeIf { it.interval.isNotEmpty() || it.webhook.isNotEmpty() }?.let {
                AutoUpdate(
                    interval = it.interval.takeIf(String::isNotEmpty),
                    webhookId = it.webhook.takeIf(String::isNotEmpty),
                    forcePullImage = it.forcePullImage,
                    forceUpdate = it.forceUpdate,
                )
            },
            env = env.orEmpty().map { EnvVar(it.name, it.value) },
            createdAt = creationDate.takeIf { it > 0 },
            updatedAt = updateDate.takeIf { it > 0 },
            updatedBy = updatedBy.takeIf { it.isNotEmpty() },
            fromTemplate = fromAppTemplate,
            prune = option?.prune ?: false,
            statusMessage = if (status == StackStatus.Error) {
                deploymentStatus?.lastOrNull()?.message?.takeIf { it.isNotEmpty() }
            } else {
                null
            },
        )
    }
}

@Serializable
private data class StackFileDto(@SerialName("StackFileContent") val content: String = "")

/**
 * Compose stacks on [endpointId]: the ones Portainer manages (`GET /api/stacks`), then external ones.
 * CE does not list external ("limited") stacks, so — like its web UI — they are derived from the
 * endpoint's containers' `com.docker.compose.project` labels, matched to managed stacks by exact name.
 */
suspend fun PortainerClient.stacks(endpointId: Int): List<Stack> {
    val response = execute("/api/stacks") { parameter("filters", """{"EndpointID":$endpointId}""") }
    val managed = if (response.status == HttpStatusCode.NoContent) {
        emptyList()
    } else {
        decodeBody(response) { response.body<List<StackDto>>() }
            .filter { it.type == COMPOSE_STACK_TYPE && it.endpointId == endpointId }
            .map { it.toModel() }
    }
    val managedNames = managed.map { it.name }.toSet()
    val external = containers(endpointId)
        .filter { it.stack != null && it.stack !in managedNames }
        .groupBy { it.stack!! }
        .map { (name, members) ->
            Stack(
                id = null,
                name = name,
                type = StackType.Compose,
                endpointId = endpointId,
                status = if (members.any { it.state == ContainerState.Running }) StackStatus.Active else StackStatus.Inactive,
                control = StackControl.Limited,
                git = null,
                autoUpdate = null,
                env = emptyList(),
                createdAt = null,
                updatedAt = null,
                updatedBy = null,
                fromTemplate = false,
            )
        }
    return managed + external
}

suspend fun PortainerClient.stack(id: Int): Stack = getJson<StackDto>("/api/stacks/$id").toModel()

/** The stack's compose file. A git stack whose settings changed without a redeploy answers 409 (CE 2.45). */
suspend fun PortainerClient.stackFile(id: Int): String = getJson<StackFileDto>("/api/stacks/$id/file").content

suspend fun PortainerClient.startStack(id: Int, endpointId: Int) {
    stackOperation("/api/stacks/$id/start", endpointId) { method = HttpMethod.Post }
}

suspend fun PortainerClient.stopStack(id: Int, endpointId: Int) {
    stackOperation("/api/stacks/$id/stop", endpointId) { method = HttpMethod.Post }
}

/**
 * Deletes a Portainer-managed stack and its containers. CE has no volume-removal option for compose
 * stacks and cannot delete external compose stacks, so neither is offered.
 */
suspend fun PortainerClient.deleteStack(id: Int, endpointId: Int) {
    stackOperation("/api/stacks/$id", endpointId) { method = HttpMethod.Delete }
}

private suspend fun PortainerClient.stackOperation(path: String, endpointId: Int, block: HttpRequestBuilder.() -> Unit) {
    execute(path) {
        parameter("endpointId", endpointId)
        timeout {
            requestTimeoutMillis = STACK_OPERATION_TIMEOUT_MS
            socketTimeoutMillis = STACK_OPERATION_TIMEOUT_MS
        }
        block()
    }
}

/**
 * Updates a web-editor stack with [file] and [env] and redeploys it (`PUT /api/stacks/{id}`). [pullImage] is sent as
 * both `RepullImageAndRedeploy` (2.36+) and the older `PullImage`, which every checked version honours. `Prune` exists
 * from CE 2.40 only, so a [prune] request is sent only when the server's version has it (an older server would ignore
 * it); prune off is the default everywhere and needs no field. From 2.41 the deploy continues after the response.
 */
suspend fun PortainerClient.updateStack(id: Int, endpointId: Int, file: String, env: List<EnvVar>, pullImage: Boolean, prune: Boolean) {
    val sendPrune = prune && stackFeatures(status().version).pruneOnUpdate
    val body = buildJsonObject {
        put("StackFileContent", file)
        put("Env", JsonArray(env.map { buildJsonObject { put("name", it.name); put("value", it.value) } }))
        if (sendPrune) put("Prune", true)
        put("RepullImageAndRedeploy", pullImage)
        put("PullImage", pullImage)
    }
    stackOperation("/api/stacks/$id", endpointId) {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

/** Git credentials sent to Portainer. [password] null keeps the password Portainer has stored; it is never logged. */
data class GitAuth(val username: String, val password: String?) {
    override fun toString(): String = "GitAuth(username=$username, password=${if (password == null) "unchanged" else "***"})"
}

/** A change of a CE 2.43+ GitOps Source's credentials: removed, or set (password null keeps the stored one). */
sealed interface GitSourceAuth {
    data object None : GitSourceAuth
    data class Basic(val auth: GitAuth) : GitSourceAuth
}

/**
 * Changes to the GitOps Source a stack uses (CE 2.43+, `PUT /api/gitops/sources/{id}`); null fields stay as they are.
 * The Source is shared: every stack using it gets the change. [interval] is the polling interval (2.44+, `""` = off).
 * The repository URL is never changed: it is read-only after creation, as in Portainer's UI.
 */
data class GitSourceUpdate(
    val tlsSkipVerify: Boolean? = null,
    val auth: GitSourceAuth? = null,
    val interval: String? = null,
)

/**
 * The full git settings of a stack (`POST /api/stacks/{id}/git`). Portainer replaces the ref, env, AutoUpdate and
 * (without a source) credentials and TLS with what is sent, so every field holds the stack's current value unless
 * the user changed it. [auth] null turns authentication off; a null password keeps the stored one. [sourceId]: the
 * stack's GitOps Source (2.43+); with it, credentials and TLS are the Source's and change through [source].
 */
data class GitSettingsRequest(
    val referenceName: String,
    val configFilePath: String,
    val additionalFiles: List<String>,
    val auth: GitAuth?,
    val tlsSkipVerify: Boolean,
    val autoUpdate: AutoUpdate?,
    val env: List<EnvVar>,
    /** The stack's saved `Option.Prune` (CE applies it to Swarm only here; sent unchanged). */
    val prune: Boolean,
    val sourceId: Int? = null,
    val source: GitSourceUpdate? = null,
) {
    // Env values and the password can be secrets.
    override fun toString(): String =
        "GitSettingsRequest(ref=$referenceName, path=$configFilePath, additional=${additionalFiles.size}, auth=$auth, " +
            "tls=$tlsSkipVerify, autoUpdate=$autoUpdate, env=${env.size}, prune=$prune, sourceId=$sourceId, source=$source)"
}

/**
 * Pull and redeploy (`PUT /api/stacks/{id}/git/redeploy`). Older servers replace the ref and env with what is sent,
 * so both always hold the current (or reviewed) values; [prune] is the stack's saved value. CE has no "force" for a
 * manual redeploy (compose redeploys always recreate).
 */
data class GitRedeployRequest(
    val referenceName: String,
    val auth: GitAuth?,
    val env: List<EnvVar>,
    val pullImage: Boolean,
    val prune: Boolean,
) {
    override fun toString(): String = "GitRedeployRequest(ref=$referenceName, auth=$auth, env=${env.size}, pullImage=$pullImage, prune=$prune)"
}

private fun envJson(env: List<EnvVar>) = JsonArray(env.map { buildJsonObject { put("name", it.name); put("value", it.value) } })

private fun autoUpdateJson(a: AutoUpdate?) = if (a == null) {
    JsonNull
} else {
    buildJsonObject {
        put("Interval", a.interval.orEmpty())
        put("Webhook", a.webhookId.orEmpty())
        put("ForceUpdate", a.forceUpdate)
        put("ForcePullImage", a.forcePullImage)
    }
}

/** Inline credentials: auth on with an empty password keeps the stored one; auth off removes them. */
private fun JsonObjectBuilder.putInlineAuth(auth: GitAuth?) {
    put("RepositoryAuthentication", auth != null)
    put("RepositoryUsername", auth?.username.orEmpty())
    put("RepositoryPassword", auth?.password.orEmpty())
}

/**
 * Saves [r] as the stack's git settings. Source changes go first to the GitOps Source (2.43+; refused on older
 * servers). Fields an older server lacks are not sent: compose paths before 2.40, `SourceID` before 2.43, the
 * Source's polling interval before 2.44. Saving does not redeploy.
 */
suspend fun PortainerClient.updateGitSettings(id: Int, endpointId: Int, r: GitSettingsRequest) {
    val features = stackFeatures(status().version)
    val sourceId = r.sourceId?.takeIf { features.savedGitCredentials }
    val sourceSaved = r.source?.let { change ->
        if (sourceId == null) throw IllegalStateException("This Portainer version has no shared git sources")
        updateGitSource(sourceId, change, intervalOnSource = features.pollingOnSource)
    } ?: false
    val body = buildJsonObject {
        put("RepositoryReferenceName", r.referenceName)
        if (features.gitPathsEditable) {
            put("ConfigFilePath", r.configFilePath)
            put("AdditionalFiles", JsonArray(r.additionalFiles.map(::JsonPrimitive)))
        }
        put("AutoUpdate", autoUpdateJson(r.autoUpdate))
        put("Env", envJson(r.env))
        put("Prune", r.prune)
        if (sourceId != null) {
            // URL, credentials and TLS are the Source's: the inline fields would be ignored.
            put("SourceID", sourceId)
        } else {
            putInlineAuth(r.auth)
            put("TLSSkipVerify", r.tlsSkipVerify)
        }
    }
    try {
        execute("/api/stacks/$id/git") {
            method = HttpMethod.Post
            parameter("endpointId", endpointId)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    } catch (e: PortainerException) {
        // The two requests aren't atomic: say what did change (the message is already redacted).
        if (sourceSaved) throw IllegalStateException("$SOURCE_SAVED_STACK_NOT_MESSAGE ${e.message}")
        throw e
    }
}

/** Prefix of the error when the shared source was updated but the stack's own settings then failed. */
const val SOURCE_SAVED_STACK_NOT_MESSAGE = "Source settings were saved, stack settings were not:"

/** True when a request was sent (something was left to change). */
private suspend fun PortainerClient.updateGitSource(sourceId: Int, change: GitSourceUpdate, intervalOnSource: Boolean): Boolean {
    val body = buildJsonObject {
        change.tlsSkipVerify?.let { put("tlsSkipVerify", it) }
        when (val auth = change.auth) {
            null -> Unit
            GitSourceAuth.None -> put("authentication", buildJsonObject { })
            is GitSourceAuth.Basic -> put(
                "authentication",
                buildJsonObject {
                    put("username", auth.auth.username)
                    // Absent keeps the stored password.
                    auth.auth.password?.let { put("password", it) }
                },
            )
        }
        if (intervalOnSource) change.interval?.let { put("interval", it) }
    }
    if (body.isEmpty()) return false
    execute("/api/gitops/sources/$sourceId") {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    return true
}

/** Pulls the repository and redeploys the stack with [r]. From 2.41 the deploy continues after the response. */
suspend fun PortainerClient.gitRedeploy(id: Int, endpointId: Int, r: GitRedeployRequest) {
    val body = buildJsonObject {
        put("RepositoryReferenceName", r.referenceName)
        putInlineAuth(r.auth)
        put("Env", envJson(r.env))
        put("Prune", r.prune)
        put("RepullImageAndRedeploy", r.pullImage)
        put("PullImage", r.pullImage)
    }
    stackOperation("/api/stacks/$id/git/redeploy", endpointId) {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

/**
 * Detaches a git stack: as CE's UI does, its current file and env are saved as a web-editor stack (which redeploys
 * it). Portainer drops the git settings and automatic updates; this is one-way.
 */
suspend fun PortainerClient.detachFromGit(id: Int, endpointId: Int) {
    if (!stackFeatures(status().version).detachFromGit) throw IllegalStateException("This Portainer version can't detach stacks from git")
    val stack = stack(id)
    if (stack.git == null) throw IllegalStateException("The stack is not deployed from git.")
    updateStack(id, endpointId, stackFile(id), stack.env, pullImage = false, prune = false)
}

/**
 * How many stacks other than [exceptStackId], on any endpoint, use the GitOps Source [sourceId]. The list has no
 * `GitSourceId`, so each git stack is read.
 */
suspend fun PortainerClient.stacksUsingGitSource(sourceId: Int, exceptStackId: Int): Int {
    val response = execute("/api/stacks")
    if (response.status == HttpStatusCode.NoContent) return 0
    val others = decodeBody(response) { response.body<List<StackDto>>() }.filter { it.gitConfig != null && it.id != exceptStackId }
    return others.count { stack(it.id).git?.sourceId == sourceId }
}
