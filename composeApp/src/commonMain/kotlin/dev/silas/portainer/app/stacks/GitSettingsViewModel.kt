package dev.silas.portainer.app.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.CONNECTION_DEPENDENCY_REMINDER
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.GitAuth
import dev.silas.portainer.core.api.GitRedeployRequest
import dev.silas.portainer.core.api.GitSettingsRequest
import dev.silas.portainer.core.api.GitSourceUpdate
import dev.silas.portainer.core.api.stacksUsingGitSource
import dev.silas.portainer.core.model.AutoUpdate
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.stacks.StackFeatures
import dev.silas.portainer.core.stacks.envDiff
import dev.silas.portainer.core.stacks.stackFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val FORCE_REDEPLOY_ALWAYS = "Force redeployment: always (Portainer recreates the containers)"
const val SAVE_SETTINGS_FIRST_MESSAGE =
    "Save or undo the other git settings first: Pull and redeploy only applies the reference and the environment variables."

@OptIn(ExperimentalUuidApi::class)
private fun randomWebhookId(): String = Uuid.random().toString()

/** Save confirmation: what is saved, the shared-source warning, and the connection reminder. */
data class GitSaveConfirm(val changes: List<String>, val sharedWarning: String?) {
    val reminder: String get() = CONNECTION_DEPENDENCY_REMINDER
}

/** What a pull and redeploy will use. [previousReferenceName] is set when the reference changes with it. */
data class GitRedeployReview(
    val referenceName: String,
    val previousReferenceName: String?,
    val composePaths: List<String>,
    val env: List<EnvVar>,
    val envChanges: List<String>,
    val pullImage: Boolean,
    /** The stack is stopped: the redeploy starts it. */
    val startsStack: Boolean,
) {
    val reminder: String get() = CONNECTION_DEPENDENCY_REMINDER

    /** CE's manual redeploy has no force switch: compose always recreates the containers. */
    val forceRedeployment: String get() = FORCE_REDEPLOY_ALWAYS

    override fun toString(): String = "GitRedeployReview(ref=$referenceName, paths=$composePaths, env=${env.size}, pullImage=$pullImage)"
}

/** The one-way detach confirmation. */
data class GitDetachConfirm(val stackName: String, val startsStack: Boolean, val unsavedEdits: Boolean) {
    val reminder: String get() = CONNECTION_DEPENDENCY_REMINDER
    val title: String get() = "Detach $stackName from git?"
    val body: String
        get() = "The stack becomes a web-editor stack with its current compose file and environment variables. Portainer " +
            "removes its repository settings and automatic updates, and redeploys it. This can't be undone." +
            (if (startsStack) " The stack is stopped, so its containers start." else "") +
            (if (unsavedEdits) " Your unsaved git settings changes are discarded." else "")
}

data class GitSettingsState(
    /** A Portainer-managed git stack is loaded. */
    val available: Boolean = false,
    val form: GitSettingsForm? = null,
    /** The stored settings: "unsaved" and the review are relative to them. */
    val original: GitSettingsForm? = null,
    val features: StackFeatures = stackFeatures(""),
    /** The stack's GitOps Source (CE 2.43+): URL, credentials and TLS (and from 2.44 polling) are shared through it. */
    val sourceId: Int? = null,
    /** Other stacks using [sourceId]; null while unknown. */
    val otherStacksOnSource: Int? = null,
    val counting: Boolean = false,
    /** The server's base URL, for the webhook URL. */
    val baseUrl: String = "",
    val saveConfirm: GitSaveConfirm? = null,
    val redeployReview: GitRedeployReview? = null,
    val detachConfirm: GitDetachConfirm? = null,
    /** Checking the stack again before an operation starts. */
    val checking: Boolean = false,
    /** The git operation running for this stack. */
    val running: StackOp? = null,
    val failure: Pair<StackOp, String>? = null,
    val notice: String? = null,
    /** Refusals (shown once). */
    val error: String? = null,
    val confirmDiscard: Boolean = false,
) {
    val passwordStored: Boolean get() = original?.authEnabled == true
    val pathsEditable: Boolean get() = features.gitPathsEditable
    val canDetach: Boolean get() = available && features.detachFromGit

    /** On 2.44+ polling is set on the Source, so a stack without one can only use the webhook. */
    val pollingAvailable: Boolean get() = !features.pollingOnSource || sourceId != null

    val webhookUrl: String?
        get() {
            val f = form ?: return null
            val id = f.webhookId ?: return null
            if (!f.autoUpdateEnabled || f.mechanism != AutoUpdateMechanism.Webhook) return null
            return features.webhookUrl(baseUrl, id)
        }

    val errors: List<String>
        get() {
            val f = form ?: return emptyList()
            val o = original ?: return emptyList()
            return f.validationErrors(o, pathsEditable)
        }

    internal val sourceFieldsChanged: Boolean get() = sourceUpdate() != null

    val settingsChanged: Boolean
        get() {
            val f = form ?: return false
            val o = original ?: return false
            return f.settingsDifferFrom(o)
        }

    val envChanged: Boolean get() = form != null && original != null && form.effectiveEnv != original.effectiveEnv
    val hasUnsavedChanges: Boolean get() = settingsChanged || envChanged
    val busy: Boolean get() = checking || running != null
    val canSave: Boolean get() = available && hasUnsavedChanges && errors.isEmpty() && !busy

    /** What the shared Source gets, or null when nothing of it changed (or the stack has none). */
    internal fun sourceUpdate(): GitSourceUpdate? {
        if (sourceId == null) return null
        val f = form ?: return null
        val o = original ?: return null
        return f.sourceUpdateFrom(o, features.pollingOnSource)
    }

    /** The warning for a change that reaches other stacks through the shared Source. */
    val sharedWarning: String?
        get() {
            if (!sourceFieldsChanged) return null
            return when (val n = otherStacksOnSource) {
                null -> "This may also change other stacks using this source (they couldn't be counted)."
                0 -> null
                1 -> "This also changes 1 other stack using this source."
                else -> "This also changes $n other stacks using this source."
            }
        }

    /** Env values and the password can be secrets. */
    override fun toString(): String =
        "GitSettingsState(available=$available, form=$form, sourceId=$sourceId, running=$running, checking=$checking, error=$error)"
}

/**
 * A git stack's settings, pull and redeploy, and detach. The stack, its protection and features come from [detail]
 * (the same screen); every change runs in the app's [StackSessions] ([StackOp.GitSettings], [StackOp.GitRedeploy],
 * [StackOp.Detach]), so it survives leaving the screen. Saving sends every setting: the stack's current value
 * unless edited. A typed password is sent once and dropped from the state as soon as the save starts.
 */
class GitSettingsViewModel(
    private val container: AppContainer,
    private val detail: StackDetailViewModel,
    private val serverId: String,
    private val endpointId: Int,
    /** The route's stack id: results for another stack that had this name are ignored. */
    private val stackId: Int?,
    name: String,
    private val newWebhookId: () -> String = ::randomWebhookId,
) : ViewModel() {
    private val _state = MutableStateFlow(GitSettingsState())
    val state: StateFlow<GitSettingsState> = _state

    private val sessions = container.stackSessions
    private val key = StackKey(serverId, endpointId, name)
    /** An operation succeeded: the next stored settings read replace the form, edited or not. */
    private var adoptAfterSuccess = false
    private var countJob: Job? = null
    private var countedSource: Int? = null

    init {
        viewModelScope.launch { detail.state.collect(::adopt) }
        viewModelScope.launch { sessions.progress(key).collect(::onProgress) }
    }

    private fun adopt(d: StackDetailState) {
        val stack = d.stack ?: return
        val fresh = if (stack.control == StackControl.Full && stack.id != null) formFrom(stack) else null
        if (fresh == null) {
            _state.update { it.copy(available = false, form = null, original = null, saveConfirm = null, redeployReview = null, detachConfirm = null) }
            return
        }
        val sourceId = stack.git?.sourceId?.takeIf { d.features.savedGitCredentials }
        val baseUrl = container.servers.servers.value.firstOrNull { it.id == serverId }?.baseUrl.orEmpty()
        val s = _state.value
        val sameStored = s.available && s.original == fresh
        // After a success the form follows what Portainer stored, once it has been read again.
        val clean = s.form == null || !s.hasUnsavedChanges || (adoptAfterSuccess && !sameStored)
        if (!sameStored) adoptAfterSuccess = false
        _state.update {
            it.copy(
                available = true,
                original = fresh,
                form = if (clean) fresh else it.form,
                features = d.features,
                sourceId = sourceId,
                baseUrl = baseUrl,
            )
        }
        if (sourceId != null && sourceId != countedSource) countOthers(sourceId, stack.id!!)
    }

    private fun countOthers(sourceId: Int, id: Int) {
        countedSource = sourceId
        countJob?.cancel()
        _state.update { it.copy(counting = true, otherStacksOnSource = null) }
        countJob = viewModelScope.launch {
            val n = try {
                container.client(serverId).stacksUsingGitSource(sourceId, exceptStackId = id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // unknown: the warning says so
            }
            _state.update { it.copy(counting = false, otherStacksOnSource = n) }
        }
    }

    private fun onProgress(p: StackProgress?) {
        val gitOp = p?.op?.let { it is StackOp.GitSettings || it is StackOp.GitRedeploy || it == StackOp.Detach } == true
        when {
            p == null || !gitOp -> _state.update { it.copy(running = null) }
            stackId == null || p.stackId != stackId -> Unit // another stack that had this name: the overview clears it
            p.running -> _state.update { it.copy(running = p.op) }
            p.error != null -> _state.update { it.copy(running = null, failure = p.op to p.error) }
            else -> {
                // The overview reads the stack again; what Portainer stored becomes the form.
                adoptAfterSuccess = true
                val notice = when (p.op) {
                    is StackOp.GitSettings -> "Git settings saved"
                    is StackOp.GitRedeploy -> "Stack redeployed"
                    else -> "Stack detached from git"
                }
                _state.update { it.copy(running = null, notice = notice) }
            }
        }
    }

    private fun editable(): Boolean {
        val s = _state.value
        val allowed = s.available && s.form != null && !s.busy && s.saveConfirm == null && s.redeployReview == null && s.detachConfirm == null
        if (allowed) adoptAfterSuccess = false
        return allowed
    }

    /** Any field edit; a webhook id is made when the webhook is chosen and there is none yet. */
    fun edit(change: GitSettingsForm.() -> GitSettingsForm) {
        if (!editable()) return
        _state.update { s ->
            val f = s.form!!.change()
            val withId = if (f.autoUpdateEnabled && f.mechanism == AutoUpdateMechanism.Webhook && f.webhookId == null) {
                f.copy(webhookId = newWebhookId())
            } else {
                f
            }
            s.copy(form = withId)
        }
    }

    fun setAutoUpdateEnabled(on: Boolean) {
        val s = _state.value
        edit { copy(autoUpdateEnabled = on, mechanism = if (on && !s.pollingAvailable) AutoUpdateMechanism.Webhook else mechanism) }
    }

    fun setMechanism(mechanism: AutoUpdateMechanism) {
        if (mechanism == AutoUpdateMechanism.Polling && !_state.value.pollingAvailable) return
        edit { copy(mechanism = mechanism) }
    }

    fun setEnvRow(index: Int, value: EnvVar) = edit { copy(env = env.mapIndexed { i, v -> if (i == index) value else v }) }

    fun addEnvRow() = edit { copy(env = env + EnvVar("", "")) }

    fun removeEnvRow(index: Int) = edit { copy(env = env.filterIndexed { i, _ -> i != index }) }

    fun setEnvBlock(text: String) = edit { copy(envBlock = text) }

    /** To the block: the rows as `.env` text. Back to rows: only from a block without errors. */
    fun setEnvMode(mode: EnvMode) {
        val f = _state.value.form ?: return
        if (mode == f.envMode) return
        when (mode) {
            EnvMode.Block -> edit { copy(envMode = mode, envBlock = formatDotEnv(effectiveEnv)) }
            EnvMode.Rows -> {
                val (env, errors) = parseDotEnv(f.envBlock)
                if (errors.isNotEmpty()) _state.update { it.copy(error = "Fix the .env lines first") } else edit { copy(envMode = mode, env = env) }
            }
        }
    }

    /** Why nothing may run on the stack now, apart from protection. */
    private fun commonRefusal(d: StackDetailState): String? {
        val stack = d.stack ?: return "The stack couldn't be loaded — try again."
        if (stack.control != StackControl.Full || stack.id == null) return EXTERNAL_STACK_MESSAGE
        if (stack.git == null) return "The stack is no longer deployed from git."
        return STACK_DEPLOYING_MESSAGE.takeIf { stack.status == StackStatus.Deploying }
            ?: sessions.refusal(serverId, endpointId)
            ?: OTHER_STACK_OPERATION_MESSAGE.takeIf { sessions.progress(key).value?.running == true }
    }

    /** Protection blocks what deploys: a redeploy, a detach, and a save that starts automatic redeploys. */
    private fun protectionRefusal(d: StackDetailState): String? = when {
        d.protected -> PROTECTED_IMAGE_MESSAGE
        d.protectionUnknown -> PROTECTION_UNKNOWN_MESSAGE
        else -> null
    }

    private fun startsAutoRedeploys(s: GitSettingsState): Boolean {
        val f = s.form ?: return false
        val o = s.original ?: return false
        return f.startsAutoRedeploysFrom(o)
    }

    private fun saveRefusal(s: GitSettingsState, d: StackDetailState): String? =
        commonRefusal(d) ?: protectionRefusal(d).takeIf { startsAutoRedeploys(s) }

    fun requestSave() {
        val s = _state.value
        if (!s.canSave || s.saveConfirm != null) return
        saveRefusal(s, detail.state.value)?.let { reason ->
            _state.update { it.copy(error = reason) }
            return
        }
        _state.update { it.copy(saveConfirm = GitSaveConfirm(changeList(s), s.sharedWarning)) }
    }

    private fun changeList(s: GitSettingsState): List<String> {
        val f = s.form ?: return emptyList()
        val o = s.original ?: return emptyList()
        return f.changesFrom(o)
    }

    fun dismissSave() = _state.update { it.copy(saveConfirm = null) }

    /** Builds the request (the typed password goes into it and out of the state), checks again, then saves. */
    fun confirmSave() {
        val s = _state.value
        if (s.saveConfirm == null) return
        val stack = detail.state.value.stack
        val request = stack?.let { buildSettings(s, it) }
        _state.update { it.copy(saveConfirm = null, checking = true, form = it.form?.copy(password = "")) }
        viewModelScope.launch {
            try {
                detail.reloadNow()
                val d = detail.state.value
                val refusal = saveRefusal(_state.value.copy(form = s.form), d)
                val id = d.stack?.id
                when {
                    refusal != null -> _state.update { it.copy(error = refusal) }
                    id == null || request == null -> _state.update { it.copy(error = "The stack couldn't be loaded — try again.") }
                    !sessions.start(key, id, StackOp.GitSettings(request)) -> _state.update {
                        it.copy(error = sessions.refusal(serverId, endpointId) ?: OTHER_STACK_OPERATION_MESSAGE)
                    }
                }
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    private fun buildSettings(s: GitSettingsState, stack: Stack): GitSettingsRequest? {
        val f = s.form ?: return null
        val o = s.original ?: return null
        val autoUpdate = if (f.autoUpdateKey == o.autoUpdateKey) {
            stack.autoUpdate // unchanged: exactly what Portainer has
        } else if (!f.autoUpdateEnabled) {
            null
        } else {
            AutoUpdate(
                interval = f.interval.trim().takeIf { f.mechanism == AutoUpdateMechanism.Polling },
                webhookId = f.webhookId.takeIf { f.mechanism == AutoUpdateMechanism.Webhook },
                forcePullImage = f.forcePullImage,
                forceUpdate = f.forceUpdate,
            )
        }
        return GitSettingsRequest(
            referenceName = f.referenceName.trim(),
            configFilePath = f.configFilePath.trim(),
            additionalFiles = f.additionalPaths,
            auth = if (f.authEnabled) GitAuth(f.username, f.password.ifEmpty { null }) else null,
            tlsSkipVerify = f.tlsSkipVerify,
            autoUpdate = autoUpdate,
            env = f.effectiveEnv,
            prune = stack.prune,
            sourceId = s.sourceId,
            source = s.sourceUpdate(),
        )
    }

    private fun redeployRefusal(d: StackDetailState): String? = commonRefusal(d) ?: protectionRefusal(d)

    /** Opens the review: the form's reference and env with the stored paths and credentials. */
    fun requestRedeploy() {
        val s = _state.value
        val f = s.form ?: return
        val o = s.original ?: return
        if (!s.available || s.busy || s.redeployReview != null) return
        val d = detail.state.value
        val refusal = redeployRefusal(d)
            // Only the reference and env go with a redeploy; anything else edited must be saved (or undone) first.
            ?: SAVE_SETTINGS_FIRST_MESSAGE.takeIf { s.copy(form = f.copy(referenceName = o.referenceName)).settingsChanged }
            ?: f.envErrors.firstOrNull()
            ?: "Enter a reference, e.g. refs/heads/main".takeIf { f.referenceName.isBlank() && o.referenceName.isNotBlank() }
        refusal?.let { reason ->
            _state.update { it.copy(error = reason) }
            return
        }
        val stack = d.stack ?: return
        val ref = f.referenceName.trim()
        val review = GitRedeployReview(
            referenceName = ref,
            previousReferenceName = o.referenceName.takeIf { it != ref },
            composePaths = listOf(o.configFilePath) + o.additionalPaths,
            env = f.effectiveEnv,
            envChanges = envDiff(o.effectiveEnv, f.effectiveEnv),
            pullImage = false,
            startsStack = stack.status == StackStatus.Inactive,
        )
        _state.update { it.copy(redeployReview = review) }
    }

    fun setRedeployPullImage(on: Boolean) = _state.update { s -> s.copy(redeployReview = s.redeployReview?.copy(pullImage = on)) }

    fun dismissRedeploy() = _state.update { it.copy(redeployReview = null) }

    fun confirmRedeploy() {
        val review = _state.value.redeployReview ?: return
        _state.update { it.copy(redeployReview = null, checking = true) }
        viewModelScope.launch {
            try {
                detail.reloadNow()
                val d = detail.state.value
                val stack = d.stack
                val git = stack?.git
                val id = stack?.id
                val refusal = redeployRefusal(d)
                when {
                    refusal != null -> _state.update { it.copy(error = refusal) }
                    stack == null || id == null || git == null -> _state.update { it.copy(error = "The stack couldn't be loaded — try again.") }
                    else -> {
                        val request = GitRedeployRequest(
                            referenceName = review.referenceName,
                            // The stored credentials: an empty password keeps (≤2.43) or uses (2.44+) them.
                            auth = if (git.authenticated) GitAuth(git.username.orEmpty(), null) else null,
                            env = review.env,
                            pullImage = review.pullImage,
                            prune = stack.prune,
                        )
                        if (!sessions.start(key, id, StackOp.GitRedeploy(request))) {
                            _state.update { it.copy(error = sessions.refusal(serverId, endpointId) ?: OTHER_STACK_OPERATION_MESSAGE) }
                        }
                    }
                }
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    fun requestDetach() {
        val s = _state.value
        if (!s.canDetach || s.busy || s.detachConfirm != null) return
        val d = detail.state.value
        redeployRefusal(d)?.let { reason ->
            _state.update { it.copy(error = reason) }
            return
        }
        val stack = d.stack ?: return
        _state.update {
            it.copy(detachConfirm = GitDetachConfirm(stack.name, startsStack = stack.status == StackStatus.Inactive, unsavedEdits = s.hasUnsavedChanges))
        }
    }

    fun dismissDetach() = _state.update { it.copy(detachConfirm = null) }

    fun confirmDetach() {
        if (_state.value.detachConfirm == null) return
        _state.update { it.copy(detachConfirm = null, checking = true) }
        viewModelScope.launch {
            try {
                detail.reloadNow()
                val d = detail.state.value
                val refusal = redeployRefusal(d) ?: "This Portainer version can't detach stacks from git.".takeIf { !d.features.detachFromGit }
                val id = d.stack?.id
                when {
                    refusal != null -> _state.update { it.copy(error = refusal) }
                    id == null -> _state.update { it.copy(error = "The stack couldn't be loaded — try again.") }
                    !sessions.start(key, id, StackOp.Detach) -> _state.update {
                        it.copy(error = sessions.refusal(serverId, endpointId) ?: OTHER_STACK_OPERATION_MESSAGE)
                    }
                }
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    /** True: leave now. False: there are unsaved edits, so [GitSettingsState.confirmDiscard] asks first. */
    fun requestBack(): Boolean {
        val s = _state.value
        if (s.hasUnsavedChanges && s.running == null) {
            _state.update { it.copy(confirmDiscard = true) }
            return false
        }
        return true
    }

    fun dismissDiscard() = _state.update { it.copy(confirmDiscard = false) }

    fun clearFailure() {
        _state.update { it.copy(failure = null) }
        sessions.clear(key)
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
        sessions.clear(key)
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
