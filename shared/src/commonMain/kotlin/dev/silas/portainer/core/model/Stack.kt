package dev.silas.portainer.core.model

/** Portainer's stack `Type` (1 Swarm, 2 Compose, 3 Kubernetes); only [Compose] is managed by this app. */
enum class StackType {
    Compose,
    Swarm,
    Kubernetes,
    Unknown,
    ;

    companion object {
        fun fromCode(code: Int): StackType = when (code) {
            1 -> Swarm
            2 -> Compose
            3 -> Kubernetes
            else -> Unknown
        }
    }
}

/**
 * Portainer's stack `Status`. [Deploying] (3) and [Error] (4) exist from CE 2.41 on, where updates and
 * redeploys run after the HTTP response; older servers only report [Active] (1) and [Inactive] (2).
 */
enum class StackStatus {
    Active,
    Inactive,
    Deploying,
    Error,
    ;

    companion object {
        fun fromCode(code: Int): StackStatus = when (code) {
            1 -> Active
            2 -> Inactive
            3 -> Deploying
            4 -> Error
            // 0 (missing, e.g. very old stacks) or a code newer than this app: never claim it is running.
            else -> Inactive
        }
    }
}

/** [Full]: created and managed by Portainer. [Limited]: "external", found only through container labels. */
enum class StackControl { Full, Limited }

/** Git settings of a git stack. Portainer never returns the password, only whether auth is set and its [username]. */
data class GitConfig(
    val url: String,
    val referenceName: String,
    val configFilePath: String,
    val additionalFiles: List<String>,
    val authenticated: Boolean,
    val username: String?,
    val tlsSkipVerify: Boolean,
    /** Commit currently deployed, when Portainer reports one. */
    val configHash: String?,
    /** CE 2.43+ GitOps Source holding URL/credentials/TLS (`GitSourceId`); only `stack(id)` returns it. */
    val sourceId: Int? = null,
)

/** GitOps automatic updates; a stack without them has `autoUpdate == null`. */
data class AutoUpdate(
    /** Polling interval as a Go duration (`5m`); null when polling is off. */
    val interval: String?,
    /** Webhook UUID; the URL is built with `StackFeatures.webhookUrl`. Null when the webhook is off. */
    val webhookId: String?,
    val forcePullImage: Boolean,
    val forceUpdate: Boolean,
)

data class EnvVar(val name: String, val value: String)

/**
 * A stack on one endpoint. External (limited-control) stacks have `id == null`, `control == Limited`
 * and no git/env/date information. Dates are unix seconds.
 */
data class Stack(
    val id: Int?,
    val name: String,
    val type: StackType,
    val endpointId: Int,
    val status: StackStatus,
    val control: StackControl,
    val git: GitConfig?,
    val autoUpdate: AutoUpdate?,
    val env: List<EnvVar>,
    val createdAt: Long?,
    val updatedAt: Long?,
    val updatedBy: String?,
    val fromTemplate: Boolean,
    /** "Prune services" saved with the stack (`Option.Prune`). */
    val prune: Boolean = false,
    /** Portainer's message for the last failed deployment when [status] is [StackStatus.Error]. */
    val statusMessage: String? = null,
)
