package dev.silas.portainer.app.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.CONNECTION_DEPENDENCY_REMINDER
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.model.EnvVar
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.recreate.isProtectedImage
import dev.silas.portainer.core.stacks.ComposeAnalysis
import dev.silas.portainer.core.stacks.ComposeWarning
import dev.silas.portainer.core.stacks.DiffHunk
import dev.silas.portainer.core.stacks.YamlError
import dev.silas.portainer.core.stacks.analyzeCompose
import dev.silas.portainer.core.stacks.envDiff
import dev.silas.portainer.core.stacks.lineDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Validation waits this long after the last keystroke. */
private const val VALIDATION_DEBOUNCE_MS = 300L

const val GIT_STACK_EDIT_MESSAGE = "This stack is deployed from git: change its file in the repository."
const val OTHER_STACK_OPERATION_MESSAGE = "Another operation is running on this stack."
const val STACK_DEPLOYING_MESSAGE = "Portainer is still deploying this stack — try again when it finishes."

/** `\r\n` and lone `\r` (YAML line breaks too) become `\n`, so lines match the editor's and the parser's. */
fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

/** What the update will change, shown before it starts. [file] and [env] are what will be sent. */
data class StackReview(
    val file: String,
    val env: List<EnvVar>,
    /** Images in [file], for the protection check right before the update starts. */
    val images: List<String>,
    val hunks: List<DiffHunk>,
    val envChanges: List<String>,
    val warnings: List<ComposeWarning>,
    /** The stored file used CRLF line ends; the update sends `\n` ones. */
    val lineEndingsChanged: Boolean,
    /** The stack is stopped: CE deploys an update with compose up, so the update starts it. */
    val startsStack: Boolean,
    val pullImage: Boolean,
    val prune: Boolean,
    /** The server has `Prune` on update (CE 2.40+); otherwise the switch is hidden. */
    val pruneAvailable: Boolean,
) {
    val reminder: String get() = CONNECTION_DEPENDENCY_REMINDER
}

data class StackEditorState(
    /** A Portainer-managed web-editor stack: external and git stacks have no editor. */
    val editable: Boolean = false,
    /** The file has been loaded into the editor. */
    val ready: Boolean = false,
    /** The stored file (line ends normalised) and env: the diff and "unsaved" are relative to them. */
    val original: String = "",
    val originalEnv: List<EnvVar> = emptyList(),
    val originalHadCarriageReturns: Boolean = false,
    val text: String = "",
    /** The rows; in [EnvMode.Block] the [envBlock] text is what counts. */
    val env: List<EnvVar> = emptyList(),
    val envMode: EnvMode = EnvMode.Rows,
    val envBlock: String = "",
    /** The last validation and the text it was run on. */
    val analysis: ComposeAnalysis? = null,
    val analyzedText: String? = null,
    val review: StackReview? = null,
    /** Checking the stack again before the update starts. */
    val checking: Boolean = false,
    /** The update is running in the stack's session. */
    val updating: Boolean = false,
    val updateError: String? = null,
    val notice: String? = null,
    /** Refusals (shown once). */
    val error: String? = null,
    val confirmDiscard: Boolean = false,
) {
    private val parsedBlock: Pair<List<EnvVar>, List<String>>? by lazy { if (envMode == EnvMode.Block) parseDotEnv(envBlock) else null }

    /** The env the update sends: the rows without blank ones, or the parsed block. */
    val effectiveEnv: List<EnvVar> by lazy {
        parsedBlock?.first ?: env.filterNot { it.name.isEmpty() && it.value.isEmpty() }
    }
    val envErrors: List<String> by lazy { parsedBlock?.second.orEmpty() + envErrors(effectiveEnv) }

    val validating: Boolean get() = editable && ready && analyzedText != text
    val yamlError: YamlError? get() = analysis?.error
    val errorLine: Int? get() = yamlError?.line
    val warnings: List<ComposeWarning> get() = analysis?.warnings.orEmpty()
    val hasUnsavedChanges: Boolean get() = ready && (text != original || effectiveEnv != originalEnv)
    val canUpdate: Boolean
        get() = editable && ready && !validating && yamlError == null && envErrors.isEmpty() && !checking && !updating
}

/**
 * Why the update may not run: everything that blocks stack operations, the new file's images (a file that adds
 * Portainer or its agent is protected too), git and external stacks, and a deploy Portainer hasn't finished.
 */
fun stackUpdateRefusal(state: StackDetailState, newImages: List<String>): String? {
    val stack = state.stack ?: return "The stack couldn't be loaded — try again."
    if (stack.control != StackControl.Full || stack.id == null) return EXTERNAL_STACK_MESSAGE
    if (stack.git != null) return GIT_STACK_EDIT_MESSAGE
    return when {
        state.protected || newImages.any(::isProtectedImage) -> PROTECTED_IMAGE_MESSAGE
        state.protectionUnknown -> PROTECTION_UNKNOWN_MESSAGE
        stack.status == StackStatus.Deploying -> STACK_DEPLOYING_MESSAGE
        else -> null
    }
}

/**
 * The compose file and env editor of a web-editor stack. The stack, its file and its protection come from [detail]
 * (the same screen); the update runs as [StackOp.Update] in the app's [StackSessions], so it survives leaving the
 * screen. A failed update keeps the edits, and an editor opened later gets them back from the failed session.
 */
class StackEditorViewModel(
    container: AppContainer,
    private val detail: StackDetailViewModel,
    private val serverId: String,
    private val endpointId: Int,
    /** The route's stack id: results for another stack that had this name are ignored. Null: external. */
    private val stackId: Int?,
    name: String,
) : ViewModel() {
    private val _state = MutableStateFlow(StackEditorState())
    val state: StateFlow<StackEditorState> = _state

    private val sessions = container.stackSessions
    private val key = StackKey(serverId, endpointId, name)
    private var validation: Job? = null
    /** A failed update's edits, put back once the file is loaded if nothing was typed meanwhile. */
    private var pendingRestore: StackOp.Update? = null
    /** An update succeeded: the next stored file and env read replace the text, edited or not. */
    private var adoptAfterSuccess = false

    init {
        viewModelScope.launch { detail.state.collect(::adopt) }
        viewModelScope.launch { sessions.progress(key).collect(::onProgress) }
    }

    /** Takes the stack's stored file and env as the baseline; the editor follows them while nothing is edited. */
    private fun adopt(d: StackDetailState) {
        val stack = d.stack ?: return
        val editable = stack.control == StackControl.Full && stack.id != null && stack.git == null
        if (!editable) {
            _state.update { it.copy(editable = false, review = null) }
            return
        }
        val file = d.file ?: return
        val normalized = normalizeLineEndings(file)
        val s = _state.value
        if (s.ready && s.editable && normalized == s.original && stack.env == s.originalEnv) return
        val clean = !s.ready || !s.hasUnsavedChanges || adoptAfterSuccess
        adoptAfterSuccess = false
        _state.update {
            it.copy(
                editable = true,
                ready = true,
                original = normalized,
                originalEnv = stack.env,
                originalHadCarriageReturns = '\r' in file,
                text = if (clean) normalized else it.text,
                env = if (clean) stack.env else it.env,
                envBlock = if (clean) formatDotEnv(stack.env) else it.envBlock,
                envMode = if (clean) EnvMode.Rows else it.envMode,
            )
        }
        applyRestore()
        validate()
    }

    private fun onProgress(p: StackProgress?) {
        when {
            p == null || p.op !is StackOp.Update -> _state.update { it.copy(updating = false) }
            stackId == null || p.stackId != stackId -> Unit // another stack that had this name: the overview clears it
            p.running -> _state.update { it.copy(updating = true) }
            p.error != null -> {
                _state.update { it.copy(updating = false, updateError = p.error) }
                pendingRestore = p.op
                applyRestore()
            }
            else -> {
                // The overview reads the stack again; what Portainer stored becomes the editor's text.
                adoptAfterSuccess = true
                _state.update { it.copy(updating = false, notice = "Stack updated") }
            }
        }
    }

    private fun applyRestore() {
        val op = pendingRestore ?: return
        val s = _state.value
        if (!s.ready) return
        pendingRestore = null
        if (s.hasUnsavedChanges) return // the edits on screen are newer (or the same)
        _state.update { it.copy(text = op.file, env = op.env, envMode = EnvMode.Rows, envBlock = formatDotEnv(op.env)) }
        validate()
    }

    /** True when an edit may be made now; an edit also keeps what was typed over a refresh after a success. */
    private fun editing(): Boolean {
        val s = _state.value
        val allowed = s.editable && s.ready && !s.updating && !s.checking && s.review == null
        if (allowed) adoptAfterSuccess = false
        return allowed
    }

    /** Pass back exactly what the code editor emitted; the text is never rewritten here. */
    fun setText(text: String) {
        if (!editing() || text == _state.value.text) return
        _state.update { it.copy(text = text) }
        validate()
    }

    private fun validate() {
        val previous = validation
        previous?.cancel()
        val text = _state.value.text
        validation = viewModelScope.launch {
            // A cancelled check may still be running off the main thread: let it end first.
            previous?.join()
            delay(VALIDATION_DEBOUNCE_MS)
            val analysis = withContext(Dispatchers.Default) { analyzeCompose(text, webEditor = true) }
            _state.update { if (it.text == text) it.copy(analysis = analysis, analyzedText = text) else it }
        }
    }

    fun setEnvRow(index: Int, value: EnvVar) {
        if (!editing()) return
        _state.update { s -> s.copy(env = s.env.mapIndexed { i, v -> if (i == index) value else v }) }
    }

    fun addEnvRow() {
        if (!editing()) return
        _state.update { it.copy(env = it.env + EnvVar("", "")) }
    }

    fun removeEnvRow(index: Int) {
        if (!editing()) return
        _state.update { s -> s.copy(env = s.env.filterIndexed { i, _ -> i != index }) }
    }

    fun setEnvBlock(text: String) {
        if (!editing()) return
        _state.update { it.copy(envBlock = text) }
    }

    /** To the block: the rows as `.env` text. Back to rows: only from a block without errors. */
    fun setEnvMode(mode: EnvMode) {
        val s = _state.value
        if (!editing() || mode == s.envMode) return
        when (mode) {
            EnvMode.Block -> _state.update { it.copy(envMode = mode, envBlock = formatDotEnv(it.effectiveEnv)) }
            EnvMode.Rows -> {
                val (env, errors) = parseDotEnv(s.envBlock)
                if (errors.isNotEmpty()) {
                    _state.update { it.copy(error = "Fix the .env lines first") }
                } else {
                    _state.update { it.copy(envMode = mode, env = env) }
                }
            }
        }
    }

    /** Opens the review, unless the update is refused (protection, external/git stack, another operation). */
    fun requestUpdate() {
        val s = _state.value
        if (!s.canUpdate || s.review != null) return
        val analysis = s.analysis ?: return
        val d = detail.state.value
        val refusal = stackUpdateRefusal(d, analysis.images)
            ?: sessions.refusal(serverId, endpointId)
            ?: OTHER_STACK_OPERATION_MESSAGE.takeIf { sessions.progress(key).value?.running == true }
        refusal?.let { reason ->
            _state.update { it.copy(error = reason) }
            return
        }
        val pruneAvailable = d.features.pruneOnUpdate
        val review = StackReview(
            file = s.text,
            env = s.effectiveEnv,
            images = analysis.images,
            hunks = lineDiff(s.original, s.text),
            envChanges = envDiff(s.originalEnv, s.effectiveEnv),
            warnings = analysis.warnings,
            lineEndingsChanged = s.originalHadCarriageReturns,
            startsStack = d.stack?.status == StackStatus.Inactive,
            pullImage = false,
            prune = pruneAvailable && d.stack?.prune == true,
            pruneAvailable = pruneAvailable,
        )
        _state.update { it.copy(review = review) }
    }

    fun setPullImage(on: Boolean) = _state.update { s -> s.copy(review = s.review?.copy(pullImage = on)) }

    fun setPrune(on: Boolean) = _state.update { s -> s.copy(review = s.review?.copy(prune = on && s.review.pruneAvailable)) }

    fun dismissReview() = _state.update { it.copy(review = null) }

    /** Reads the stack again (members, file, protection), checks again, then starts the update in the session. */
    fun confirmUpdate() {
        val review = _state.value.review ?: return
        _state.update { it.copy(review = null, checking = true) }
        viewModelScope.launch {
            try {
                detail.reloadNow()
                val d = detail.state.value
                val refusal = stackUpdateRefusal(d, review.images) ?: sessions.refusal(serverId, endpointId)
                val id = d.stack?.id
                val op = StackOp.Update(review.file, review.env, review.pullImage, review.prune && review.pruneAvailable)
                when {
                    refusal != null -> _state.update { it.copy(error = refusal) }
                    id == null -> _state.update { it.copy(error = "The stack couldn't be loaded — try again.") }
                    !sessions.start(key, id, op) -> _state.update {
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

    /** True: leave now. False: there are unsaved edits, so [StackEditorState.confirmDiscard] asks first. */
    fun requestBack(): Boolean {
        val s = _state.value
        // A running update carries on (and keeps its edits) without the screen.
        if (s.hasUnsavedChanges && !s.updating) {
            _state.update { it.copy(confirmDiscard = true) }
            return false
        }
        return true
    }

    fun dismissDiscard() = _state.update { it.copy(confirmDiscard = false) }

    fun clearUpdateError() {
        _state.update { it.copy(updateError = null) }
        sessions.clear(key)
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
        sessions.clear(key)
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
