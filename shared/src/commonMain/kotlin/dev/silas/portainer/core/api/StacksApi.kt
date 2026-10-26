package dev.silas.portainer.core.api

import dev.silas.portainer.core.model.AutoUpdate
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.GitConfig
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.model.StackType
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
