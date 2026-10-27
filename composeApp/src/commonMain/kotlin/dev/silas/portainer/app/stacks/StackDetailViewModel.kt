package dev.silas.portainer.app.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.stack
import dev.silas.portainer.core.api.stackFile
import dev.silas.portainer.core.api.stacks
import dev.silas.portainer.core.api.status
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.Stack
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.resources.resolveIdOnlyImages
import dev.silas.portainer.core.stacks.StackFeatures
import dev.silas.portainer.core.stacks.analyzeCompose
import dev.silas.portainer.core.stacks.stackContainers
import dev.silas.portainer.core.stacks.stackFeatures
import dev.silas.portainer.core.stacks.stackProtection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StackDetailState(
    val stack: Stack? = null,
    /** Members by compose project label, running and stopped. */
    val containers: List<ContainerSummary> = emptyList(),
    /** The compose file; null for external stacks or when it could not be loaded ([fileError]). */
    val file: String? = null,
    val fileError: String? = null,
    /** The stack runs Portainer (members or compose file images): lifecycle actions are blocked. */
    val protected: Boolean = false,
    val features: StackFeatures = stackFeatures(""),
    val loading: Boolean = true,
    /** Load failures and refused actions (shown once). */
    val error: String? = null,
    /**
     * A Portainer-managed stack whose compose file couldn't be read: the file's images are unknown, so whether it
     * runs Portainer is too, and stop/delete are refused until a reload reads it.
     */
    val protectionUnknown: Boolean = false,
    /** The confirmation on screen. */
    val confirm: StackConfirm? = null,
    /** The operation running in [AppContainer.stackSessions] for this stack. */
    val running: StackOp? = null,
    /** Checking the stack again before an operation starts. */
    val starting: Boolean = false,
    /** A finished operation that failed: what it was and Portainer's (or the connection's) message. */
    val failure: Pair<StackOp, String>? = null,
    /** A finished start/stop, confirmed by Portainer. */
    val notice: String? = null,
    /** The stack was deleted: the screen goes back to the list. */
    val deleted: Boolean = false,
) {
    val ops: List<StackOp> get() = availableStackOps(this)
}

/** One stack: a Portainer-managed one by [stackId], or an external one ([stackId] null) by [name]. */
class StackDetailViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val stackId: Int?,
    /** The stack's name (from the route): the title while loading, and its session key. */
    val name: String,
) : ViewModel() {
    private val _state = MutableStateFlow(StackDetailState())
    val state: StateFlow<StackDetailState> = _state

    private var loadJob: Job? = null
    private val sessions = container.stackSessions
    private val key = StackKey(serverId, endpointId, name)

    init {
        refresh()
        // Re-attaches to an operation started from an earlier screen, running or finished meanwhile.
        viewModelScope.launch {
            sessions.progress(key).collect { p ->
                when {
                    p == null -> _state.update { it.copy(running = null) }
                    // A result for another stack that had this name (deleted, then re-created): stale.
                    stackId != null && p.stackId != stackId -> if (!p.running) sessions.clear(key)
                    p.running -> _state.update { it.copy(running = p.op) }
                    stackId == null -> Unit // external: Portainer can't act on it, so nothing ran on it
                    else -> finished(p)
                }
            }
        }
    }

    private val StackOp.shownElsewhere: Boolean
        get() = this is StackOp.Update || this is StackOp.GitSettings || this is StackOp.GitRedeploy || this == StackOp.Detach

    /** Shows a finished result; the session forgets it once the user has seen it ([clearFailure], [clearNotice], [navigated]). */
    private fun finished(p: StackProgress) {
        val error = p.error
        when {
            // The editor (update) and the git tab (settings, redeploy, detach) show these results and keep the edits;
            // here the stack is only read again.
            p.op.shownElsewhere -> {
                _state.update { it.copy(running = null) }
                refresh()
            }
            error != null -> {
                _state.update { it.copy(running = null, failure = p.op to error) }
                refresh()
            }
            p.op == StackOp.Delete -> _state.update { it.copy(running = null, deleted = true) }
            else -> {
                val notice = if (p.op == StackOp.Start) "Stack started" else "Stack stopped"
                _state.update { it.copy(running = null, notice = notice) }
                refresh()
            }
        }
    }

    /**
     * Start runs at once; stop and delete ask first. A refused operation only says why. [unsavedEdits]: the editor
     * holds edits that a delete would throw away, so its confirmation says so.
     */
    fun request(op: StackOp, unsavedEdits: Boolean = false) {
        val s = _state.value
        if (s.running != null || s.starting || s.confirm != null || op !in s.ops) return
        (stackOpRefusal(s, op) ?: sessions.refusal(serverId, endpointId))?.let { reason ->
            _state.update { it.copy(error = reason) }
            return
        }
        val stack = s.stack ?: return
        if (needsConfirm(op)) _state.update { it.copy(confirm = StackConfirm(op, stack.name, unsavedEdits = unsavedEdits)) } else launchOp(op)
    }

    fun setTypedName(text: String) = _state.update { s -> s.copy(confirm = s.confirm?.copy(typedName = text)) }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    fun confirm() {
        val confirm = _state.value.confirm ?: return
        if (!confirm.canConfirm) return
        _state.update { it.copy(confirm = null) }
        launchOp(confirm.op)
    }

    /** Reads the stack again (members, file, protection), then starts [op] in the app-scoped session. */
    private fun launchOp(op: StackOp) {
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                // Joined, so a cancelled load never lands after this one.
                loadJob?.cancelAndJoin()
                load()
                val s = _state.value
                val refusal = stackOpRefusal(s, op)
                    ?: "The stack changed — check it and try again.".takeIf { op !in s.ops }
                    ?: sessions.refusal(serverId, endpointId)
                val id = s.stack?.id
                when {
                    refusal != null -> _state.update { it.copy(error = refusal) }
                    id == null -> _state.update { it.copy(error = "The stack couldn't be loaded — try again.") }
                    !sessions.start(key, id, op) -> _state.update {
                        it.copy(error = sessions.refusal(serverId, endpointId) ?: "Another operation is running on this stack.")
                    }
                }
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            } finally {
                _state.update { it.copy(starting = false, loading = false) }
            }
        }
    }

    fun clearFailure() {
        _state.update { it.copy(failure = null) }
        sessions.clear(key)
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
        sessions.clear(key)
    }

    /** The screen went back to the list after the delete. */
    fun navigated() {
        _state.update { it.copy(deleted = false) }
        sessions.clear(key)
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        val previous = loadJob
        previous?.cancel()
        loadJob = viewModelScope.launch {
            // A cancelled load may still be finishing off the main thread: let it end before this one starts.
            previous?.join()
            try {
                load()
            } catch (e: Exception) {
                val message = failureMessage(e)
                _state.update { it.copy(error = message) }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Reads the stack again now, for a check right before an operation starts; failures are thrown. */
    internal suspend fun reloadNow() {
        loadJob?.cancelAndJoin()
        try {
            load()
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun load() {
        val client = container.client(serverId)
        val stack = if (stackId != null) {
            client.stack(stackId)
        } else {
            client.stacks(endpointId).firstOrNull { it.control == StackControl.Limited && it.name == name }
                ?: throw IllegalStateException("Stack $name no longer exists")
        }
        var file: String? = null
        var fileError: String? = null
        val id = stack.id
        if (id != null) {
            try {
                file = client.stackFile(id)
            } catch (e: Exception) {
                // e.g. 409 for a git stack whose settings changed without a redeploy; the rest still shows.
                fileError = failureMessage(e, "Couldn't load the compose file")
            }
        }
        val members = stackContainers(stack.name, client.containers(endpointId)).sortedBy { it.name.lowercase() }
        val resolved = client.resolveIdOnlyImages(endpointId, members)
        val images = file?.let { text ->
            withContext(Dispatchers.Default) { analyzeCompose(text, webEditor = stack.git == null).images }
        }.orEmpty()
        val features = try {
            stackFeatures(client.status().version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stackFeatures("") // unknown version: every optional feature off
        }
        _state.update {
            it.copy(
                stack = stack,
                containers = members,
                file = file,
                fileError = fileError,
                protected = stackProtection(members, images, resolved),
                protectionUnknown = stack.control == StackControl.Full && file == null,
                features = features,
                error = null,
            )
        }
    }
}
