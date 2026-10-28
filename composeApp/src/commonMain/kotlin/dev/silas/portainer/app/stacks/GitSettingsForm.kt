package dev.silas.portainer.app.stacks

import dev.silas.portainer.core.api.GitAuth
import dev.silas.portainer.core.api.GitSourceAuth
import dev.silas.portainer.core.api.GitSourceUpdate
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.stacks.envDiff

const val INTERVAL_ERROR = "Use a duration of at least 1 minute, e.g. 5m, 1h or 1h30m"
const val PASSWORD_REQUIRED_ERROR = "Enter the password or token"

/** CE runs automatic updates by polling the repository or by a webhook, not both. */
enum class AutoUpdateMechanism { Polling, Webhook }

/** A Go duration of hours, minutes and seconds (`5m`, `1h30m`, `90s`) of at least one minute, as Portainer requires. */
fun isValidInterval(text: String): Boolean {
    val m = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""").matchEntire(text) ?: return false
    if (text.isEmpty()) return false
    val (h, min, s) = m.destructured
    val seconds = (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
    return seconds >= 60
}

/** The git settings form. [password] is only what was typed (empty = keep the stored one); it is never shown back. */
data class GitSettingsForm(
    /** Shown only: the repository URL is read-only after creation, as in Portainer. */
    val url: String,
    val referenceName: String,
    val configFilePath: String,
    /** One path per line. */
    val additionalFiles: String,
    val authEnabled: Boolean,
    val username: String,
    val password: String,
    val tlsSkipVerify: Boolean,
    val autoUpdateEnabled: Boolean,
    val mechanism: AutoUpdateMechanism,
    val interval: String,
    val webhookId: String?,
    val forceUpdate: Boolean,
    val forcePullImage: Boolean,
    val env: List<EnvVar>,
    val envMode: EnvMode = EnvMode.Rows,
    val envBlock: String = "",
) {
    private val parsedBlock: Pair<List<EnvVar>, List<String>>? by lazy { if (envMode == EnvMode.Block) parseDotEnv(envBlock) else null }

    /** The env that is sent: the rows without blank ones, or the parsed block. */
    val effectiveEnv: List<EnvVar> by lazy { parsedBlock?.first ?: env.filterNot { it.name.isEmpty() && it.value.isEmpty() } }
    val envErrors: List<String> by lazy { parsedBlock?.second.orEmpty() + envErrors(effectiveEnv) }

    val additionalPaths: List<String> get() = additionalFiles.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The source polling interval this form means (`""` = off). */
    val pollingInterval: String get() = if (autoUpdateEnabled && mechanism == AutoUpdateMechanism.Polling) interval.trim() else ""

    /** Only what is saved, so switching views or leaving a hidden field as it was is no change. */
    internal val autoUpdateKey: List<Any?>
        get() = if (!autoUpdateEnabled) {
            listOf(false)
        } else {
            listOf(true, mechanism, if (mechanism == AutoUpdateMechanism.Polling) interval.trim() else webhookId, forceUpdate, forcePullImage)
        }

    internal val authKey: List<Any?> get() = if (authEnabled) listOf(true, username) else listOf(false)

    /** Env values and the password can be secrets. */
    override fun toString(): String =
        "GitSettingsForm(ref=$referenceName, path=$configFilePath, auth=$authEnabled, password=${if (password.isEmpty()) "unchanged" else "***"}, " +
            "tls=$tlsSkipVerify, autoUpdate=$autoUpdateKey, env=${effectiveEnv.size})"
}

internal fun formFrom(stack: Stack): GitSettingsForm? {
    val git = stack.git ?: return null
    val a = stack.autoUpdate
    return GitSettingsForm(
        url = git.url,
        referenceName = git.referenceName,
        configFilePath = git.configFilePath,
        additionalFiles = git.additionalFiles.joinToString("\n"),
        authEnabled = git.authenticated,
        username = git.username.orEmpty(),
        password = "",
        tlsSkipVerify = git.tlsSkipVerify,
        autoUpdateEnabled = a != null,
        mechanism = if (a?.webhookId != null) AutoUpdateMechanism.Webhook else AutoUpdateMechanism.Polling,
        interval = a?.interval ?: "5m",
        webhookId = a?.webhookId,
        forceUpdate = a?.forceUpdate ?: false,
        forcePullImage = a?.forcePullImage ?: false,
        env = stack.env,
        envBlock = formatDotEnv(stack.env),
    )
}


/** Errors that block saving [this] over the stored [o]. */
internal fun GitSettingsForm.validationErrors(o: GitSettingsForm, pathsEditable: Boolean): List<String> = buildList {
    if (referenceName.isBlank() && o.referenceName.isNotBlank()) add("Enter a reference, e.g. refs/heads/main")
    if (pathsEditable && configFilePath.isBlank()) add("Enter the compose file path")
    if (authEnabled && !o.authEnabled && password.isEmpty()) add(PASSWORD_REQUIRED_ERROR)
    if (autoUpdateEnabled && mechanism == AutoUpdateMechanism.Polling && !isValidInterval(interval.trim())) add(INTERVAL_ERROR)
    addAll(envErrors)
}

/** A git setting (anything but the env) differs from the stored [o]; a typed password counts. */
internal fun GitSettingsForm.settingsDifferFrom(o: GitSettingsForm): Boolean =
    referenceName != o.referenceName || configFilePath != o.configFilePath || additionalPaths != o.additionalPaths ||
        authKey != o.authKey || password.isNotEmpty() || tlsSkipVerify != o.tlsSkipVerify || autoUpdateKey != o.autoUpdateKey

/**
 * Saving [this] over the stored [o] can make Portainer redeploy the stack by itself: automatic updates turned on, or
 * polling started (also webhook → polling while on); with automatic updates staying on, a changed reference or compose
 * path (the next poll or webhook deploys it), a changed env with Force redeployment on (it is deployed even when the
 * repository didn't change), or Force redeployment turned on (the next poll or webhook call recreates the containers).
 */
internal fun GitSettingsForm.mayAutoRedeployFrom(o: GitSettingsForm): Boolean {
    if (!autoUpdateEnabled) return false
    val turnedOn = !o.autoUpdateEnabled || (pollingInterval.isNotEmpty() && o.pollingInterval.isEmpty())
    val deployedFilesChanged = referenceName.trim() != o.referenceName.trim() || configFilePath.trim() != o.configFilePath.trim() ||
        additionalPaths != o.additionalPaths
    val forcedEnv = forceUpdate && effectiveEnv != o.effectiveEnv
    val forcedOn = forceUpdate && !o.forceUpdate
    return turnedOn || deployedFilesChanged || forcedEnv || forcedOn
}

/** What the shared GitOps Source gets for [this] over [o], or null when none of its fields changed. */
internal fun GitSettingsForm.sourceUpdateFrom(o: GitSettingsForm, pollingOnSource: Boolean): GitSourceUpdate? {
    val auth = when {
        authKey == o.authKey && password.isEmpty() -> null
        !authEnabled -> GitSourceAuth.None
        else -> GitSourceAuth.Basic(GitAuth(username, password.ifEmpty { null }))
    }
    val update = GitSourceUpdate(
        tlsSkipVerify = tlsSkipVerify.takeIf { it != o.tlsSkipVerify },
        auth = auth,
        interval = pollingInterval.takeIf { pollingOnSource && it != o.pollingInterval },
    )
    return update.takeIf { it != GitSourceUpdate() }
}

/** The changes listed in the save confirmation. */
internal fun GitSettingsForm.changesFrom(o: GitSettingsForm): List<String> = buildList {
    if (referenceName != o.referenceName) add("Reference: ${referenceName.ifEmpty { "default branch" }}")
    if (configFilePath != o.configFilePath || additionalPaths != o.additionalPaths) add("Compose paths")
    if (authKey != o.authKey || password.isNotEmpty()) add(if (authEnabled) "Authentication" else "Authentication off")
    if (tlsSkipVerify != o.tlsSkipVerify) add(if (tlsSkipVerify) "Skip TLS verification" else "Verify TLS")
    if (autoUpdateKey != o.autoUpdateKey) add(if (autoUpdateEnabled) "Automatic updates" else "Automatic updates off")
    addAll(envDiff(o.effectiveEnv, effectiveEnv))
}
