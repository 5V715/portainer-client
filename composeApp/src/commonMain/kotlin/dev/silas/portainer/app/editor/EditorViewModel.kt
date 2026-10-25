package dev.silas.portainer.app.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.api.networks
import dev.silas.portainer.core.api.registries
import dev.silas.portainer.core.api.volumes
import dev.silas.portainer.core.editor.ContainerForm
import dev.silas.portainer.core.editor.FormField
import dev.silas.portainer.core.editor.FormMerger
import dev.silas.portainer.core.editor.FormParser
import dev.silas.portainer.core.editor.MountOrigin
import dev.silas.portainer.core.editor.MountRow
import dev.silas.portainer.core.editor.MountType
import dev.silas.portainer.core.editor.NetworkRow
import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.recreate.isProtectedImage
import dev.silas.portainer.core.registry.matchRegistry
import dev.silas.portainer.app.recreate.SessionKey
import dev.silas.portainer.core.api.inspectImage
import dev.silas.portainer.core.recreate.RecreatePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** [Edit]: form pre-filled from a container (Replace / Deploy as copy). [New]: empty form (Create). */
enum class EditorMode { Edit, New }

/** How the user wants to finish; the flows themselves (review, conflicts, progress) are the finish step's. */
enum class FinishAction { Replace, Copy, Create }

sealed interface EditorPhase {
    data object Loading : EditorPhase
    data class Error(val message: String) : EditorPhase
    data object Ready : EditorPhase
}

private val EMPTY = JsonObject(emptyMap())

/** Docker uses the first 12 characters of the id as hostname and network alias. */
private const val SHORT_ID_LENGTH = 12

/** Docker's default network; a new container is attached to it unless the user picks another. */
private val BRIDGE = NetworkRow("bridge", emptyList(), "", "", "")

data class EditorState(
    val phase: EditorPhase = EditorPhase.Loading,
    val mode: EditorMode = EditorMode.Edit,
    /** The container's inspect (Edit), `null` for New. */
    val original: JsonObject? = null,
    /** The form as loaded; [dirty] and the typed MACs/aliases are relative to it. */
    val before: ContainerForm = FormParser.empty(),
    /** The form as the user left it (typed values; invalid text keeps [before]'s value, see [errors]). */
    val form: ContainerForm = FormParser.empty(),
    /** What the screen shows and edits. */
    val draft: EditorDraft = EditorDraft.of(FormParser.empty()),
    /** Sections whose form values differ from [before] (as `FormMerger.merge` reports them). */
    val dirty: Set<FormField> = emptySet(),
    val errors: Map<EditorSection, List<String>> = emptyMap(),
    /** Sections the user changed something in: their errors show right away, the others after [EditorViewModel.review]. */
    val touched: Set<EditorSection> = emptySet(),
    val showErrors: Boolean = false,
    /** Set for a container running Portainer: Replace is unavailable; the form can be edited for a copy. */
    val protectedMessage: String? = null,
    val pull: Boolean = false,
    /** [pull] and [registryId] as loaded, to tell whether they were changed. */
    val initialPull: Boolean = false,
    val initialRegistryId: Int? = null,
    /** New only. */
    val startAfterCreate: Boolean = true,
    val registries: List<Registry> = emptyList(),
    val registryId: Int? = null,
    /** Named volumes for the volume picker (anonymous ones left out). */
    val volumes: List<String> = emptyList(),
    /** Network names for the network picker. */
    val networks: List<String> = emptyList(),
    /** `HostConfig.Binds` indices whose strings can't be rewritten without loss (`FormMerger.isEditableInPlace`). */
    val lockedBinds: Set<Int> = emptySet(),
    /** Container paths of anonymous volumes that aren't form rows (image `VOLUME`). */
    val anonymousVolumes: List<String> = emptyList(),
    /** The read-only Advanced section. */
    val advanced: List<Pair<String, String>> = emptyList(),
    /** The finish step (review, deploy progress); `null` while on the form. The form can't be edited meanwhile. */
    val finish: FinishPhase? = null,
    /** Where the screen should go now (see [EditorViewModel.navigated]). */
    val navigation: EditorNavigation? = null,
) {
    private val ready get() = phase == EditorPhase.Ready

    /** Replace is unavailable for Portainer's own container and when the form would make it run Portainer. */
    val canReplace get() = ready && mode == EditorMode.Edit && protectedMessage == null && !isProtectedImage(form.image)
    val canCopy get() = ready && mode == EditorMode.Edit
    val canCreate get() = ready && mode == EditorMode.New

    /** The form takes edits (not while the finish step is shown). */
    val editable get() = ready && finish == null

    /** Anything the user would lose by leaving: form edits, or a changed pull switch or registry. */
    val hasUnsavedChanges get() = ready && (dirty.isNotEmpty() || pull != initialPull || registryId != initialRegistryId)

    /** A row whose edits couldn't be written back; shown read-only (removing it still works). */
    fun isLocked(row: MountRow) = row.origin == MountOrigin.Binds && row.originalIndex in lockedBinds

    /** Errors the screen shows for [section] now. */
    fun visibleErrors(section: EditorSection): List<String> =
        if (showErrors || section in touched) errors[section].orEmpty() else emptyList()
}

/**
 * The Edit / Duplicate / New container form. [containerId] `null` = New. Loading, editing and validation live
 * here; [review] is the hand-over point to the finish flows (Replace, Deploy as copy, Create).
 */
class EditorViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val containerId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState(mode = if (containerId == null) EditorMode.New else EditorMode.Edit))
    val state: StateFlow<EditorState> = _state

    /** The draft as loaded, so unchanged text keeps the loaded values exactly. */
    private var initial: EditorDraft = EditorDraft.of(FormParser.empty())
    private var pullTouched = false
    private var registryPicked = false

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val message = try {
            val client = container.client(serverId)
            coroutineScope {
                val inspect = containerId?.let { id -> async { client.inspectContainer(endpointId, id) } }
                // The pickers are conveniences: an account that can't list these still gets the form.
                val registries = async { optional { client.registries() } }
                val volumes = async { optional { client.volumes(endpointId) } }
                val networks = async { optional { client.networks(endpointId) } }
                ready(
                    inspect?.await(),
                    registries.await(),
                    volumes.await().filterNot { it.anonymous }.map { it.name },
                    networks.await().map { it.name },
                )
            }
            null
        } catch (e: Exception) {
            failureMessage(e)
        }
        if (message != null) _state.update { it.copy(phase = EditorPhase.Error(message)) }
    }

    private suspend fun <T> optional(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private fun ready(inspect: JsonObject?, registries: List<Registry>, volumes: List<String>, networks: List<String>) {
        val before = inspect?.let(FormParser::fromInspect) ?: FormParser.empty().copy(primaryNetwork = BRIDGE)
        initial = EditorDraft.of(before)
        val mode = if (inspect == null) EditorMode.New else EditorMode.Edit
        _state.update {
            it.copy(
                phase = EditorPhase.Ready,
                mode = mode,
                original = inspect,
                before = before,
                form = before,
                draft = initial,
                dirty = emptySet(),
                errors = validate(initial, initial, mode),
                protectedMessage = PROTECTED_IMAGE_MESSAGE.takeIf { inspect != null && isProtectedImage(before.image) },
                pull = mode == EditorMode.New,
                initialPull = mode == EditorMode.New,
                registries = registries,
                registryId = matchFor(before.image, registries),
                initialRegistryId = matchFor(before.image, registries),
                volumes = volumes,
                networks = networks,
                lockedBinds = inspect?.let { i ->
                    before.mounts.filter { r -> r.origin == MountOrigin.Binds && !FormMerger.isEditableInPlace(i, r) }
                        .mapNotNull { r -> r.originalIndex }.toSet()
                }.orEmpty(),
                anonymousVolumes = inspect?.let { anonymousVolumeTargets(it, before.mounts) }.orEmpty(),
                advanced = inspect?.let(::advancedSummary).orEmpty(),
            )
        }
    }

    private fun matchFor(image: String, registries: List<Registry>): Int? =
        if (image.isBlank()) null else matchRegistry(ImageRef.parse(image.trim()), registries)?.id

    /** Applies [block] to the draft and recomputes the form, dirty sections and errors; ignored unless editable. */
    private fun edit(section: EditorSection, block: (EditorState) -> EditorState) = _state.update { s ->
        if (!s.editable) return@update s
        val next = block(s)
        val form = next.draft.toForm(s.before, initial)
        next.copy(
            form = form,
            dirty = FormMerger.merge(s.original ?: EMPTY, s.before, form).second,
            errors = validate(next.draft, initial, s.mode),
            touched = s.touched + section,
        )
    }

    private fun editDraft(section: EditorSection, block: (EditorDraft) -> EditorDraft) =
        edit(section) { it.copy(draft = block(it.draft)) }

    // ---- image ----

    /** Pull follows "the image changed" in Edit until the user sets it; the registry follows the image until picked. */
    fun setImage(image: String) = edit(EditorSection.Image) { s ->
        s.copy(
            draft = s.draft.copy(image = image),
            pull = if (pullTouched || s.mode == EditorMode.New) s.pull else image.trim() != s.before.image,
            registryId = if (registryPicked) s.registryId else matchFor(image, s.registries),
        )
    }

    fun setPull(pull: Boolean) {
        pullTouched = true
        edit(EditorSection.Image) { it.copy(pull = pull) }
    }

    /** Picks a registry by id, or none (public) with null. Stops the automatic match for this screen. */
    fun setRegistry(id: Int?) {
        registryPicked = true
        edit(EditorSection.Image) { it.copy(registryId = id) }
    }

    // ---- general ----

    fun setName(name: String) = editDraft(EditorSection.General) { it.copy(name = name) }
    fun setCommand(text: String) = editDraft(EditorSection.General) { it.copy(command = text) }
    fun setEntrypoint(text: String) = editDraft(EditorSection.General) { it.copy(entrypoint = text) }
    fun setWorkingDir(text: String) = editDraft(EditorSection.General) { it.copy(workingDir = text) }
    fun setUser(text: String) = editDraft(EditorSection.General) { it.copy(user = text) }

    // ---- ports ----

    fun addPort() = editDraft(EditorSection.Ports) { it.copy(ports = it.ports + PortDraft("", "", "", "tcp")) }
    fun updatePort(index: Int, port: PortDraft) = editDraft(EditorSection.Ports) { d ->
        if (index !in d.ports.indices) d else d.copy(ports = d.ports.toMutableList().also { it[index] = port })
    }
    fun removePort(index: Int) = editDraft(EditorSection.Ports) { d -> d.copy(ports = d.ports.filterIndexed { i, _ -> i != index }) }
    fun setPublishAll(on: Boolean) = editDraft(EditorSection.Ports) { it.copy(publishAll = on) }

    // ---- mounts ----

    fun addMount() = editDraft(EditorSection.Volumes) {
        it.copy(mounts = it.mounts + MountRow(MountType.Volume, "", "", readOnly = false, origin = MountOrigin.New, originalIndex = null))
    }

    /**
     * Replaces row [index], keeping where it came from. A locked row ignores edits. A `Binds` row's type follows
     * its source (host path → bind, name or empty → volume): the `-v` string can't express anything else.
     */
    fun updateMount(index: Int, row: MountRow) = edit(EditorSection.Volumes) { s ->
        val old = s.draft.mounts.getOrNull(index) ?: return@edit s
        if (s.isLocked(old)) return@edit s
        var next = row.copy(origin = old.origin, originalIndex = old.originalIndex)
        if (next.origin == MountOrigin.Binds) next = next.copy(type = if (isAbsolutePath(next.source)) MountType.Bind else MountType.Volume)
        s.copy(draft = s.draft.copy(mounts = s.draft.mounts.toMutableList().also { it[index] = next }))
    }

    fun removeMount(index: Int) = editDraft(EditorSection.Volumes) { d -> d.copy(mounts = d.mounts.filterIndexed { i, _ -> i != index }) }

    // ---- networks ----

    /** Adds a network row (the first one is the primary network). */
    fun addNetwork() = editDraft(EditorSection.Networks) {
        if (it.networkModeReadOnly != null) it else it.copy(networks = it.networks + NetworkDraft("", "", "", "", ""))
    }
    fun updateNetwork(index: Int, network: NetworkDraft) = editDraft(EditorSection.Networks) { d ->
        if (d.networkModeReadOnly != null || index !in d.networks.indices) d
        else d.copy(networks = d.networks.toMutableList().also { it[index] = network })
    }
    fun removeNetwork(index: Int) = editDraft(EditorSection.Networks) { d -> d.copy(networks = d.networks.filterIndexed { i, _ -> i != index }) }

    // ---- env, labels ----

    /** Edits row [index]; an entry that had no `=` and still has no value stays without one. */
    fun setEnvRow(index: Int, key: String, value: String) = editDraft(EditorSection.Env) { d ->
        val old = d.env.getOrNull(index) ?: return@editDraft d
        // An entry loaded without `=` and left without a value goes back to its key-only form.
        val entry = if (value.isEmpty() && ('=' !in old || key in initial.env)) key else "$key=$value"
        d.copy(env = d.env.toMutableList().also { it[index] = entry })
    }
    fun addEnvRow() = editDraft(EditorSection.Env) { it.copy(env = it.env + "=") }
    fun removeEnvRow(index: Int) = editDraft(EditorSection.Env) { d -> d.copy(env = d.env.filterIndexed { i, _ -> i != index }) }

    fun setLabel(index: Int, key: String, value: String) = editDraft(EditorSection.Labels) { d ->
        if (index !in d.labels.indices) d else d.copy(labels = d.labels.toMutableList().also { it[index] = key to value })
    }
    fun addLabel() = editDraft(EditorSection.Labels) { it.copy(labels = it.labels + ("" to "")) }
    fun removeLabel(index: Int) = editDraft(EditorSection.Labels) { d -> d.copy(labels = d.labels.filterIndexed { i, _ -> i != index }) }

    // ---- restart, resources ----

    fun setRestartPolicy(name: String) = editDraft(EditorSection.Restart) { it.copy(restartName = name) }
    fun setMaxRetries(text: String) = editDraft(EditorSection.Restart) { it.copy(maxRetries = text) }
    fun setMemory(text: String) = editDraft(EditorSection.Resources) { it.copy(memory = text) }
    fun setMemoryReservation(text: String) = editDraft(EditorSection.Resources) { it.copy(memoryReservation = text) }
    fun setCpus(text: String) = editDraft(EditorSection.Resources) { it.copy(cpus = text) }
    fun setCpuShares(text: String) = editDraft(EditorSection.Resources) { it.copy(cpuShares = text) }

    fun setStartAfterCreate(on: Boolean) = _state.update {
        if (it.mode == EditorMode.New && it.finish == null) it.copy(startAfterCreate = on) else it
    }

    // ---- for the finish flows ----

    /**
     * MAC addresses the user typed: form MACs that none of the loaded networks had (Copy keeps only these).
     * Compared against all loaded values, so moving a row to another network doesn't make its generated MAC "typed".
     */
    fun typedMacs(): Set<String> {
        val s = _state.value
        val loaded = s.before.networkRows().map { it.mac }.toSet()
        return s.form.networkRows().map { it.mac }.filter { it.isNotEmpty() && it !in loaded }.toSet()
    }

    /** Network aliases the user typed: form aliases that no loaded network had, never the container's short id. */
    fun typedAliases(): Set<String> {
        val s = _state.value
        val loaded = s.before.networkRows().flatMap { it.aliases }.toSet()
        val shortId = (s.original?.get("Id") as? JsonPrimitive)?.content?.take(SHORT_ID_LENGTH)
        return s.form.networkRows().flatMap { it.aliases }.filter { it !in loaded && it != shortId }.toSet()
    }

    private fun ContainerForm.networkRows() = listOfNotNull(primaryNetwork) + extraNetworks

    /**
     * "Replace" / "Deploy as copy" / "Create": validates and returns whether [action] may go ahead. With errors,
     * every section's errors are shown.
     *
     * [finish] runs this first.
     */
    fun review(action: FinishAction): Boolean {
        val s = _state.value
        val allowed = when (action) {
            FinishAction.Replace -> s.canReplace
            FinishAction.Copy -> s.canCopy
            FinishAction.Create -> s.canCreate
        }
        if (!allowed) return false
        if (s.errors.isNotEmpty()) {
            _state.update { it.copy(showErrors = true) }
            return false
        }
        return true
    }

    // ---- finish: review, then Replace (RecreateSessions) or deploy (DeploySessions) ----

    private var reviewContext: ReviewContext? = null
    private var built: BuiltReview? = null
    private var copyStart: Boolean? = null
    private var checkJob: Job? = null
    private var deployJob: Job? = null
    private var deployKey: DeployKey? = null

    /** Copies are keyed by their original, new containers by [NEW_SOURCE]; the editor for that source shows them again. */
    private val deploySource get() = containerId ?: NEW_SOURCE

    init {
        // A deploy from this source still running, or finished without being acknowledged (the user left): show it.
        container.deploySessions.find(serverId, endpointId, deploySource)?.let { attach(it, live = false) }
    }

    /**
     * "Replace" / "Deploy as copy" / "Create": validates ([review]), then re-inspects the container (Replace,
     * Copy) and the endpoint's containers and networks, and shows the review. False when the form has errors.
     */
    fun finish(action: FinishAction): Boolean {
        if (_state.value.finish != null || !review(action)) return false
        copyStart = null
        _state.update { it.copy(finish = FinishPhase.Checking(action)) }
        checkJob = viewModelScope.launch {
            val phase = try {
                check(action)
            } catch (e: Exception) {
                // Rethrows the CancellationException of a cancelled check (Back, or a new check started).
                FinishPhase.Failed(action, failureMessage(e))
            }
            // A check cancelled after its last request never overwrites the form or a newer check.
            ensureActive()
            _state.update { if (it.finish is FinishPhase.Checking) it.copy(finish = phase) else it }
        }
        return true
    }

    private suspend fun check(action: FinishAction): FinishPhase {
        val s = _state.value
        val client = container.client(serverId)
        var ctx = if (action == FinishAction.Create) {
            ReviewContext(action, null, null, null, NO_INPUTS, notice = null, blocked = null)
        } else {
            val fresh = client.inspectContainer(endpointId, checkNotNull(containerId))
            val imageConfig = (fresh["Image"] as? JsonPrimitive)?.content?.let { client.inspectImage(endpointId, it) }
            val freshBefore = FormParser.fromInspect(fresh)
            // Sections that changed outside the editor; the edits are merged onto the fresh inspect.
            val outside = FormMerger.merge(fresh, s.before, freshBefore).second
            val stale = outside intersect s.dirty
            val blocked = when {
                action == FinishAction.Replace && isProtectedImage(freshBefore.image) -> PROTECTED_IMAGE_MESSAGE
                stale.isNotEmpty() -> staleEditsMessage(stale)
                action == FinishAction.Replace -> container.recreateSessions.refusal(serverId, endpointId)
                else -> null
            }
            val changed = outside.isNotEmpty() || fresh["Image"] != s.original?.get("Image") || running(fresh) != running(s.original)
            ReviewContext(action, fresh, imageConfig, freshBefore, NO_INPUTS, CONTAINER_CHANGED_NOTICE.takeIf { changed }, blocked, stale)
        }
        val name = when (action) {
            FinishAction.Copy -> s.form.name.takeIf { it.isNotEmpty() && it != s.before.name } ?: "${s.before.name}-copy"
            else -> s.form.name
        }
        // A first build only to learn which networks get a fixed IP (those are inspected for their members).
        val draft = rebuild(ctx, s, name)
        val body = draft.plan?.createBody ?: draft.request?.body ?: JsonObject(emptyMap())
        val extras = draft.plan?.extraNetworks ?: draft.request?.extraNetworks.orEmpty()
        ctx = ctx.with(conflictInputs(client, endpointId, fixedIpNetworks(body, extras)))
        reviewContext = ctx
        return rebuild(ctx, s, name).also { built = it }.review
    }

    private fun rebuild(ctx: ReviewContext, s: EditorState, name: String) =
        buildReview(ctx, s, name, typedMacs(), typedAliases(), copyStart)

    private fun running(inspect: JsonObject?) = (inspect?.get("State") as? JsonObject)?.get("Running")

    /** Rebuilds the review from what was fetched (no requests). */
    private fun updateReview(change: (FinishPhase.Review) -> String) {
        val ctx = reviewContext ?: return
        val s = _state.value
        val review = s.finish as? FinishPhase.Review ?: return
        if (review.starting) return
        val next = rebuild(ctx, s, change(review))
        built = next
        _state.update { it.copy(finish = next.review) }
    }

    /** Deploy as copy: the copy's name, checked again against the containers already listed. */
    fun setDeployName(name: String) = updateReview { r -> if (r.nameEditable) name.trim() else r.name }

    /** Deploy as copy: whether the copy is started (default: as the original). */
    fun setDeployStart(on: Boolean) = updateReview { r ->
        if (r.startEditable) copyStart = on
        r.name
    }

    /**
     * "Reload current settings" on a review blocked by edits made on outdated settings: the form is reloaded from the
     * container's current inspect, keeping the edits of sections that didn't change outside; the others are lost.
     */
    fun reloadCurrent() {
        val ctx = reviewContext ?: return
        val fresh = ctx.fresh ?: return
        val freshBefore = ctx.freshBefore ?: return
        val review = _state.value.finish as? FinishPhase.Review ?: return
        if (review.stale.isEmpty() || review.starting) return
        initial = EditorDraft.of(freshBefore)
        _state.update { s ->
            val form = withSections(freshBefore, s.form, s.dirty - review.stale)
            val draft = EditorDraft.of(form)
            val typed = draft.toForm(freshBefore, initial)
            s.copy(
                original = fresh,
                before = freshBefore,
                form = typed,
                draft = draft,
                dirty = FormMerger.merge(fresh, freshBefore, typed).second,
                errors = validate(draft, initial, s.mode),
                protectedMessage = PROTECTED_IMAGE_MESSAGE.takeIf { isProtectedImage(freshBefore.image) },
                lockedBinds = freshBefore.mounts.filter { r -> r.origin == MountOrigin.Binds && !FormMerger.isEditableInPlace(fresh, r) }
                    .mapNotNull { r -> r.originalIndex }.toSet(),
                anonymousVolumes = anonymousVolumeTargets(fresh, freshBefore.mounts),
                advanced = advancedSummary(fresh),
                finish = null,
            )
        }
        reviewContext = null
        built = null
    }

    /**
     * Back from the finish step. To the form (edits kept) from the review, a failed check or a failed deploy (its
     * result is acknowledged); a successful deploy goes on to its container. Not while starting or deploying.
     */
    fun backToForm() {
        val finish = _state.value.finish ?: return
        when (finish) {
            is FinishPhase.Review -> if (finish.starting) return
            is FinishPhase.Deploying -> when (val result = finish.progress.result) {
                null -> return
                is DeployResult.Success -> {
                    _state.update { it.copy(navigation = EditorNavigation.OpenContainer(result.id)) }
                    return
                }
                is DeployResult.Failure -> Unit
            }
            else -> Unit
        }
        checkJob?.cancel()
        detach()
        reviewContext = null
        built = null
        _state.update { it.copy(finish = null) }
    }

    /** Stops showing the deploy and forgets its finished result. */
    private fun detach() {
        deployJob?.cancel()
        deployJob = null
        deployKey?.let(container.deploySessions::clear)
        deployKey = null
    }

    /** Runs the reviewed action: Replace in RecreateSessions, Copy / Create in DeploySessions. */
    fun confirm() {
        val review = _state.value.finish as? FinishPhase.Review ?: return
        val built = built ?: return
        if (!review.canConfirm) return
        _state.update { it.copy(finish = review.copy(starting = true)) }
        viewModelScope.launch {
            val refused = try {
                if (review.action == FinishAction.Replace) startReplace(checkNotNull(built.plan)) else startDeploy(checkNotNull(built.request))
            } catch (e: Exception) {
                failureMessage(e) // rethrows when this confirm was cancelled
            }
            ensureActive()
            if (refused != null) {
                _state.update { s ->
                    val r = s.finish as? FinishPhase.Review ?: return@update s
                    s.copy(finish = r.copy(blocked = refused, starting = false))
                }
            }
        }
    }

    /** @return why it couldn't start, or null once the recreate runs. */
    private suspend fun startReplace(plan: RecreatePlan): String? {
        val id = checkNotNull(containerId)
        val sessions = container.recreateSessions
        sessions.refusal(serverId, endpointId)?.let { return it }
        val key = SessionKey(serverId, endpointId, id)
        if (sessions.progress(key).value?.result != null) sessions.clear(key)
        if (sessions.progress(key).value != null) return "This container is being recreated already."
        // A client of its own, owned by the session: saving or deleting the server can't close it mid-recreate.
        if (!sessions.start(key, container.dedicatedClient(serverId), id, plan)) {
            return sessions.refusal(serverId, endpointId) ?: "This container is being recreated already."
        }
        _state.update { it.copy(navigation = EditorNavigation.ReplaceStarted) }
        return null
    }

    /** @return why it couldn't start, or null once the deploy runs (its progress is then shown). */
    private suspend fun startDeploy(request: DeployRequest): String? {
        val sessions = container.deploySessions
        val key = DeployKey(serverId, endpointId, deploySource, request.name.ifEmpty { sessions.unnamedId() })
        if (!sessions.start(key, container.dedicatedClient(serverId), request)) {
            return if (request.name.isEmpty()) "This container is being created already." else "A container named ${request.name} is being created already."
        }
        attach(key, live = true)
        return null
    }

    /**
     * Shows the deploy [key]. A [live] one (started here) opens its container on success; one found when the editor
     * opened shows its result and waits for the user.
     */
    private fun attach(key: DeployKey, live: Boolean) {
        deployJob?.cancel()
        deployKey = key
        var sawRunning = live
        deployJob = viewModelScope.launch {
            container.deploySessions.progress(key).collect { p ->
                if (p == null) return@collect
                if (p.result == null) sawRunning = true
                val success = (p.result as? DeployResult.Success)?.takeIf { sawRunning }
                _state.update {
                    it.copy(finish = FinishPhase.Deploying(p), navigation = success?.let { r -> EditorNavigation.OpenContainer(r.id) } ?: it.navigation)
                }
            }
        }
    }

    /** After a failure that left the created container: open it. */
    fun openCreated() {
        val failure = (_state.value.finish as? FinishPhase.Deploying)?.progress?.result as? DeployResult.Failure ?: return
        val id = failure.createdId ?: return
        _state.update { it.copy(navigation = EditorNavigation.OpenContainer(id)) }
    }

    /** The screen performed [EditorState.navigation]; a finished deploy's result is acknowledged. */
    fun navigated() {
        val replaced = _state.value.navigation == EditorNavigation.ReplaceStarted
        _state.update { it.copy(navigation = null) }
        if (replaced) {
            // The editor stays under the progress: Back from a failed Replace returns to the form with the edits
            // (a successful one navigates past the editor).
            checkJob?.cancel()
            reviewContext = null
            built = null
            _state.update { it.copy(finish = null) }
            return
        }
        val key = deployKey ?: return
        if (container.deploySessions.progress(key).value?.result != null) detach()
    }
}
