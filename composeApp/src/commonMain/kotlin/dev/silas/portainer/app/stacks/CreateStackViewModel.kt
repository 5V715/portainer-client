package dev.silas.portainer.app.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.PickedFile
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.CustomTemplate
import dev.silas.portainer.core.api.GitAuth
import dev.silas.portainer.core.api.GitSettingsRequest
import dev.silas.portainer.core.api.TemplateVariable
import dev.silas.portainer.core.api.customTemplateFile
import dev.silas.portainer.core.api.customTemplates
import dev.silas.portainer.core.api.stacks
import dev.silas.portainer.core.api.status
import dev.silas.portainer.core.model.AutoUpdate
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.recreate.isProtectedImage
import dev.silas.portainer.core.stacks.ComposeAnalysis
import dev.silas.portainer.core.stacks.ComposeWarning
import dev.silas.portainer.core.stacks.StackFeatures
import dev.silas.portainer.core.stacks.YamlError
import dev.silas.portainer.core.stacks.analyzeCompose
import dev.silas.portainer.core.stacks.stackFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Validation waits this long after the last keystroke. */
private const val CREATE_VALIDATION_DEBOUNCE_MS = 300L

/** Portainer CE's own wording (`NameField.tsx`). */
const val STACK_NAME_RULE_MESSAGE =
    "This field must consist of lower case alphanumeric characters, '_' or '-' (e.g. 'my-name', or 'abc-123')."
const val STACK_NAME_REQUIRED_MESSAGE = "Name is required"
const val STACK_NAME_TAKEN_MESSAGE = "Name should be unique"
const val REPOSITORY_URL_REQUIRED_MESSAGE = "Enter the repository URL"
const val PROTECTED_IMAGE_CREATE_WARNING =
    "This file runs Portainer or its agent. Creating the stack doesn't touch the Portainer you are connected to, " +
        "but the new stack's start, stop, update and delete will be blocked."

/** `STACK_NAME_VALIDATION_REGEX` of CE's UI. */
private val STACK_NAME = Regex("^[-_a-z0-9]+$")

/** Why [name] can't name a stack (Portainer's rule: lower-case letters, digits, `-` and `_`), or null. */
fun validStackName(name: String): String? = when {
    name.isEmpty() -> STACK_NAME_REQUIRED_MESSAGE
    !STACK_NAME.matches(name) -> STACK_NAME_RULE_MESSAGE
    else -> null
}

/** [validStackName], and the name must not be used on the endpoint yet ([existing]: managed and external stacks). */
fun stackNameError(name: String, existing: Collection<String>): String? =
    validStackName(name) ?: STACK_NAME_TAKEN_MESSAGE.takeIf { existing.any { it.equals(name, ignoreCase = true) } }

/** `{{{ NAME }}}`, `{{& NAME }}` and `{{ NAME }}` (Mustache variable tags; spaces optional). */
private val TEMPLATE_TAG = Regex("""\{\{\{\s*([^{}]+?)\s*\}\}\}|\{\{\s*&?\s*([^{}]+?)\s*\}\}""")

/**
 * Fills a custom template's [variables] into [file] as CE's UI does (Mustache without HTML escaping): the entered
 * value, else the variable's default, else the tag stays as `{{ NAME }}`. Tags that aren't template variables are
 * left exactly as written (CE's Mustache would blank them, which would break Go-template strings in a compose file).
 */
fun renderCustomTemplate(file: String, variables: List<TemplateVariable>, values: Map<String, String>): String {
    val byName = variables.associateBy { it.name }
    return TEMPLATE_TAG.replace(file) { m ->
        val name = m.groups[1]?.value ?: m.groups[2]?.value
        val variable = byName[name] ?: return@replace m.value
        values[variable.name].orEmpty().ifEmpty { variable.defaultValue }.ifEmpty { "{{ ${variable.name} }}" }
    }
}

/** Where the new stack's compose file comes from. Uploads and templates fill the web editor; create is the same. */
enum class CreateMethod { Editor, Upload, Repository, Template }

/** The stack that was created: the screen opens it. */
data class CreatedStack(val id: Int, val name: String)

/** A blank repository form (the env lives in [CreateStackState]). */
internal fun newGitForm() = GitSettingsForm(
    url = "",
    referenceName = "",
    configFilePath = "docker-compose.yml",
    additionalFiles = "",
    authEnabled = false,
    username = "",
    password = "",
    tlsSkipVerify = false,
    autoUpdateEnabled = false,
    mechanism = AutoUpdateMechanism.Polling,
    interval = "5m",
    webhookId = null,
    forceUpdate = false,
    forcePullImage = false,
    env = emptyList(),
)

data class CreateStackState(
    val method: CreateMethod = CreateMethod.Editor,
    val name: String = "",
    /** Stacks on the endpoint, Portainer-managed and external (from container labels). */
    val existingNames: Set<String> = emptySet(),
    val loadingNames: Boolean = true,
    val features: StackFeatures = stackFeatures(""),
    /** The server's base URL, for the webhook URL. */
    val baseUrl: String = "",
    // Web editor (also filled by an upload or a template).
    val text: String = "",
    /** The uploaded file or the template the text came from. */
    val sourceName: String? = null,
    /** The text came from a custom template: the create says so (`FromAppTemplate`), as CE's UI does. */
    val fromTemplate: Boolean = false,
    val analysis: ComposeAnalysis? = null,
    /** The text [analysis] was run on (the empty start needs none). */
    val analyzedText: String? = "",
    // Environment (every method).
    val env: List<EnvVar> = emptyList(),
    val envMode: EnvMode = EnvMode.Rows,
    val envBlock: String = "",
    // Repository.
    val git: GitSettingsForm = newGitForm(),
    // Custom templates.
    val templates: List<CustomTemplate>? = null,
    val loadingTemplates: Boolean = false,
    val selectedTemplate: CustomTemplate? = null,
    /**
     * The selected template's file with its variables' defaults filled in (CE has no variable inputs: they are
     * Business Edition only); null while loading.
     */
    val templateFile: String? = null,
    /** Checking the name and the endpoint right before the create starts. */
    val checking: Boolean = false,
    /** The create is running in the app's stack sessions. */
    val running: Boolean = false,
    /**
     * The create failed (the form is kept); [failedStackId] is set when Portainer created the stack anyway (its
     * deployment didn't finish), so it can be opened.
     */
    val failure: String? = null,
    val failedStackId: Int? = null,
    val created: CreatedStack? = null,
    /** Refusals and load failures (shown once). */
    val error: String? = null,
    val confirmDiscard: Boolean = false,
) {
    private val parsedBlock: Pair<List<EnvVar>, List<String>>? by lazy { if (envMode == EnvMode.Block) parseDotEnv(envBlock) else null }

    /** The env that is sent: the rows without blank ones, or the parsed block. */
    val effectiveEnv: List<EnvVar> by lazy { parsedBlock?.first ?: env.filterNot { it.name.isEmpty() && it.value.isEmpty() } }
    val envErrors: List<String> by lazy { parsedBlock?.second.orEmpty() + envErrors(effectiveEnv) }

    val nameError: String? get() = stackNameError(name, existingNames)

    val isText: Boolean get() = method != CreateMethod.Repository
    val validating: Boolean get() = isText && analyzedText != text
    val yamlError: YamlError? get() = analysis?.error
    val warnings: List<ComposeWarning> get() = if (isText) analysis?.warnings.orEmpty() else emptyList()

    /** A protected image in the new file is allowed (nothing running is touched) but said. */
    val protectedWarning: String?
        get() = PROTECTED_IMAGE_CREATE_WARNING.takeIf { isText && analysis?.images.orEmpty().any(::isProtectedImage) }

    /** On 2.44+ polling is set on the GitOps Source, which a new stack doesn't have yet: only the webhook. */
    val pollingAvailable: Boolean get() = !features.pollingOnSource

    val gitErrors: List<String>
        get() = buildList {
            if (git.url.isBlank()) add(REPOSITORY_URL_REQUIRED_MESSAGE)
            if (git.authEnabled && git.password.isEmpty()) add(PASSWORD_REQUIRED_ERROR)
            if (git.autoUpdateEnabled && git.mechanism == AutoUpdateMechanism.Polling && !isValidInterval(git.interval.trim())) add(INTERVAL_ERROR)
        }

    val webhookUrl: String?
        get() {
            val id = git.webhookId ?: return null
            if (!git.autoUpdateEnabled || git.mechanism != AutoUpdateMechanism.Webhook) return null
            return features.webhookUrl(baseUrl, id)
        }

    val busy: Boolean get() = checking || running

    val canCreate: Boolean
        get() {
            if (busy || loadingNames || nameError != null || envErrors.isNotEmpty()) return false
            return when (method) {
                CreateMethod.Repository -> gitErrors.isEmpty()
                else -> text.isNotBlank() && !validating && yamlError == null
            }
        }

    val hasUnsavedChanges: Boolean
        get() = name.isNotEmpty() || text.isNotEmpty() || effectiveEnv.isNotEmpty() || git.copy(webhookId = null) != newGitForm()

    // Env values and the password can be secrets.
    override fun toString(): String =
        "CreateStackState(method=$method, name=$name, lines=${text.lines().size}, env=${effectiveEnv.size}, git=$git, " +
            "running=$running, checking=$checking, failure=$failure, created=$created, error=$error)"
}

/**
 * Creates a stack on one endpoint: from the web editor (typed, uploaded or from a custom template) or from a git
 * repository. The create runs as [StackOp.Create] in the app's [StackSessions], so it survives leaving the screen
 * and a screen opened later re-attaches to it. A failed create keeps the form; a typed git password is sent once
 * and dropped from the state as soon as the create starts.
 */
class CreateStackViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val newWebhookId: () -> String = ::randomWebhookId,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateStackState())
    val state: StateFlow<CreateStackState> = _state

    private val sessions = container.stackSessions
    private var validation: Job? = null
    private var attachJob: Job? = null
    private var attached: StackKey? = null

    init {
        val baseUrl = container.servers.servers.value.firstOrNull { it.id == serverId }?.baseUrl.orEmpty()
        _state.update { it.copy(baseUrl = baseUrl) }
        sessions.pendingCreate(serverId, endpointId)?.let { key ->
            sessions.progress(key).value?.let { restore(key, it.op) }
            attach(key)
        }
        viewModelScope.launch {
            try {
                val client = container.client(serverId)
                val features = try {
                    stackFeatures(client.status().version)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stackFeatures("") // unknown version: every optional feature off
                }
                _state.update { it.copy(features = features) }
                loadNames()
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            } finally {
                _state.update { it.copy(loadingNames = false) }
            }
        }
    }

    private suspend fun loadNames(): Set<String> {
        val names = container.client(serverId).stacks(endpointId).map { it.name }.toSet()
        _state.update { it.copy(existingNames = names) }
        return names
    }

    /** A re-attached create's values become the form (never a password: progress holds none). */
    private fun restore(key: StackKey, op: StackOp) {
        when (op) {
            is StackOp.Create.Text -> _state.update {
                it.copy(method = CreateMethod.Editor, name = key.stackName, text = op.file, env = op.env, envMode = EnvMode.Rows, fromTemplate = op.fromTemplate)
            }
            is StackOp.Create.Repository -> {
                val r = op.request
                val a = r.autoUpdate
                val form = newGitForm().copy(
                    url = op.url,
                    referenceName = r.referenceName,
                    configFilePath = r.configFilePath,
                    additionalFiles = r.additionalFiles.joinToString("\n"),
                    authEnabled = r.auth != null,
                    username = r.auth?.username.orEmpty(),
                    tlsSkipVerify = r.tlsSkipVerify,
                    autoUpdateEnabled = a != null,
                    mechanism = if (a?.webhookId != null) AutoUpdateMechanism.Webhook else AutoUpdateMechanism.Polling,
                    interval = a?.interval ?: "5m",
                    webhookId = a?.webhookId,
                    forceUpdate = a?.forceUpdate ?: false,
                    forcePullImage = a?.forcePullImage ?: false,
                )
                _state.update { it.copy(method = CreateMethod.Repository, name = key.stackName, git = form, env = r.env, envMode = EnvMode.Rows) }
            }
            else -> return
        }
        validate()
    }

    private fun attach(key: StackKey) {
        attached = key
        attachJob?.cancel()
        attachJob = viewModelScope.launch { sessions.progress(key).collect { onProgress(key, it) } }
    }

    private fun onProgress(key: StackKey, p: StackProgress?) {
        when {
            p == null || p.op !is StackOp.Create -> _state.update { it.copy(running = false) }
            p.running -> _state.update { it.copy(running = true) }
            p.error != null -> {
                val id = p.stackId.takeIf { it != STACK_NOT_CREATED }
                _state.update { it.copy(running = false, failure = p.error, failedStackId = id) }
                if (id == null) findCreatedAnyway(key.stackName)
            }
            else -> _state.update { it.copy(running = false, created = CreatedStack(p.stackId, key.stackName)) }
        }
    }

    /**
     * The request failed before Portainer answered (e.g. the connection dropped): the stack may exist all the same.
     * If the endpoint now has a managed stack of that name, the failure offers to open it.
     */
    private fun findCreatedAnyway(name: String) {
        viewModelScope.launch {
            val id = try {
                container.client(serverId).stacks(endpointId).firstOrNull { it.name == name && it.id != null }?.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // unknown: the failure stays as it is
            }
            if (id != null) _state.update { if (it.failure != null && it.failedStackId == null) it.copy(failedStackId = id) else it }
        }
    }

    /** True when the form may change now. */
    private fun editable(): Boolean = !_state.value.busy

    fun setMethod(method: CreateMethod) {
        if (!editable()) return
        _state.update { it.copy(method = method) }
        if (method == CreateMethod.Template && _state.value.templates == null) loadTemplates()
        if (method != CreateMethod.Repository) validate()
    }

    fun setName(name: String) {
        if (!editable()) return
        _state.update { it.copy(name = name) }
    }

    /** Pass back exactly what the code editor emitted; the text is never rewritten here. */
    fun setText(text: String) {
        if (!editable() || text == _state.value.text) return
        // An emptied editor holds a new file, not the template's.
        _state.update { it.copy(text = text, fromTemplate = it.fromTemplate && text.isNotEmpty(), sourceName = it.sourceName.takeIf { text.isNotEmpty() }) }
        validate()
    }

    /** An uploaded file replaces the editor's text (line ends normalised once, here) and opens the editor. */
    fun loadFile(picked: PickedFile) {
        when (picked) {
            is PickedFile.Failed -> _state.update { it.copy(error = picked.message) }
            is PickedFile.Text -> {
                if (!editable()) return
                _state.update {
                    it.copy(method = CreateMethod.Editor, text = normalizeLineEndings(picked.text), sourceName = picked.name, fromTemplate = false)
                }
                validate()
            }
        }
    }

    private fun validate() {
        val previous = validation
        previous?.cancel()
        val text = _state.value.text
        validation = viewModelScope.launch {
            // A cancelled check may still be running off the main thread: let it end first.
            previous?.join()
            delay(CREATE_VALIDATION_DEBOUNCE_MS)
            val analysis = withContext(Dispatchers.Default) { analyzeCompose(text, webEditor = true) }
            _state.update { if (it.text == text) it.copy(analysis = analysis, analyzedText = text) else it }
        }
    }

    fun setEnvRow(index: Int, value: EnvVar) {
        if (!editable()) return
        _state.update { s -> s.copy(env = s.env.mapIndexed { i, v -> if (i == index) value else v }) }
    }

    fun addEnvRow() {
        if (!editable()) return
        _state.update { it.copy(env = it.env + EnvVar("", "")) }
    }

    fun removeEnvRow(index: Int) {
        if (!editable()) return
        _state.update { s -> s.copy(env = s.env.filterIndexed { i, _ -> i != index }) }
    }

    fun setEnvBlock(text: String) {
        if (!editable()) return
        _state.update { it.copy(envBlock = text) }
    }

    /** To the block: the rows as `.env` text. Back to rows: only from a block without errors. */
    fun setEnvMode(mode: EnvMode) {
        val s = _state.value
        if (!editable() || mode == s.envMode) return
        when (mode) {
            EnvMode.Block -> _state.update { it.copy(envMode = mode, envBlock = formatDotEnv(it.effectiveEnv)) }
            EnvMode.Rows -> {
                val (env, errors) = parseDotEnv(s.envBlock)
                if (errors.isNotEmpty()) _state.update { it.copy(error = "Fix the .env lines first") } else _state.update { it.copy(envMode = mode, env = env) }
            }
        }
    }

    /** Any repository field; a webhook id is made when the webhook is chosen and there is none yet. */
    fun editGit(change: GitSettingsForm.() -> GitSettingsForm) {
        if (!editable()) return
        _state.update { s ->
            val f = s.git.change()
            val withId = if (f.autoUpdateEnabled && f.mechanism == AutoUpdateMechanism.Webhook && f.webhookId == null) {
                f.copy(webhookId = newWebhookId())
            } else {
                f
            }
            s.copy(git = withId)
        }
    }

    fun setAutoUpdateEnabled(on: Boolean) {
        val s = _state.value
        editGit { copy(autoUpdateEnabled = on, mechanism = if (on && !s.pollingAvailable) AutoUpdateMechanism.Webhook else mechanism) }
    }

    fun setMechanism(mechanism: AutoUpdateMechanism) {
        if (mechanism == AutoUpdateMechanism.Polling && !_state.value.pollingAvailable) return
        editGit { copy(mechanism = mechanism) }
    }

    fun loadTemplates() {
        if (_state.value.loadingTemplates) return
        _state.update { it.copy(loadingTemplates = true) }
        viewModelScope.launch {
            try {
                val templates = container.client(serverId).customTemplates().sortedBy { it.title.lowercase() }
                _state.update { it.copy(templates = templates) }
            } catch (e: Exception) {
                val message = failureMessage(e, "Couldn't load the custom templates")
                _state.update { it.copy(error = message) }
            } finally {
                _state.update { it.copy(loadingTemplates = false) }
            }
        }
    }

    /**
     * Reads [template]'s file (a git-backed one from its repository, as CE does) and fills in its variables' defaults:
     * CE offers no variable inputs (Business Edition only), so the defaults are what it deploys too.
     */
    fun selectTemplate(template: CustomTemplate) {
        if (!editable()) return
        _state.update { it.copy(selectedTemplate = template, templateFile = null) }
        viewModelScope.launch {
            try {
                val file = container.client(serverId).customTemplateFile(template.id, git = template.gitBacked)
                val rendered = renderCustomTemplate(normalizeLineEndings(file), template.variables, emptyMap())
                _state.update { if (it.selectedTemplate == template) it.copy(templateFile = rendered) else it }
            } catch (e: Exception) {
                val message = failureMessage(e, "Couldn't load the template's file")
                _state.update { if (it.selectedTemplate == template) it.copy(selectedTemplate = null, error = message) else it }
            }
        }
    }

    fun clearTemplate() = _state.update { it.copy(selectedTemplate = null, templateFile = null) }

    /** Opens the template's file in the web editor, where it is checked (and can be changed) before the create. */
    fun useTemplate() {
        val s = _state.value
        val template = s.selectedTemplate ?: return
        val file = s.templateFile ?: return
        if (!editable()) return
        _state.update { it.copy(method = CreateMethod.Editor, text = file, sourceName = template.title, fromTemplate = true) }
        validate()
    }

    /** Checks the name against the endpoint's stacks again, then starts the create in the app's stack sessions. */
    fun create() {
        val s = _state.value
        if (!s.canCreate) return
        val name = s.name
        val op = if (s.method == CreateMethod.Repository) StackOp.Create.Repository(s.git.url.trim(), gitRequest(s)) else StackOp.Create.Text(s.text, s.effectiveEnv, fromTemplate = s.fromTemplate)
        _state.update { it.copy(checking = true, failure = null, failedStackId = null) }
        viewModelScope.launch {
            try {
                val names = loadNames()
                val refusal = stackNameError(name, names)
                    ?: sessions.refusal(serverId, endpointId)
                    ?: STACK_OPERATION_IN_PROGRESS_MESSAGE.takeIf { sessions.isRunning(serverId, endpointId) }
                val key = StackKey(serverId, endpointId, name)
                when {
                    refusal != null -> _state.update { it.copy(error = refusal) }
                    !sessions.create(key, op) -> _state.update {
                        it.copy(error = sessions.refusal(serverId, endpointId) ?: STACK_OPERATION_IN_PROGRESS_MESSAGE)
                    }
                    else -> {
                        // The password went into the request only; once it is on its way the form forgets it.
                        _state.update { it.copy(git = it.git.copy(password = "")) }
                        attached?.takeIf { it != key }?.let(sessions::clear)
                        attach(key)
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

    private fun gitRequest(s: CreateStackState): GitSettingsRequest {
        val f = s.git
        val autoUpdate = if (!f.autoUpdateEnabled) {
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
            auth = if (f.authEnabled) GitAuth(f.username, f.password) else null,
            tlsSkipVerify = f.tlsSkipVerify,
            autoUpdate = autoUpdate,
            env = s.effectiveEnv,
            prune = false,
        )
    }

    /** The screen opened the new stack: its result is consumed. */
    fun navigated() {
        val created = _state.value.created ?: return
        _state.update { it.copy(created = null) }
        sessions.clear(StackKey(serverId, endpointId, created.name))
    }

    /** True: leave now. False: there are unsaved edits, so [CreateStackState.confirmDiscard] asks first. */
    fun requestBack(): Boolean {
        val s = _state.value
        // A running create carries on without the screen.
        if (s.hasUnsavedChanges && !s.busy) {
            _state.update { it.copy(confirmDiscard = true) }
            return false
        }
        return true
    }

    fun dismissDiscard() = _state.update { it.copy(confirmDiscard = false) }

    fun clearFailure() {
        _state.update { it.copy(failure = null, failedStackId = null) }
        attached?.let(sessions::clear)
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
