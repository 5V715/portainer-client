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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

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
    /** Set for a container running Portainer: the form is read-only and only Deploy as copy is offered. */
    val readOnlyMessage: String? = null,
    val pull: Boolean = false,
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
) {
    private val ready get() = phase == EditorPhase.Ready

    /** Replace is unavailable for Portainer's own container and when the form would make it run Portainer. */
    val canReplace get() = ready && mode == EditorMode.Edit && readOnlyMessage == null && !isProtectedImage(form.image)
    val canCopy get() = ready && mode == EditorMode.Edit
    val canCreate get() = ready && mode == EditorMode.New
    val readOnly get() = readOnlyMessage != null

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
                readOnlyMessage = PROTECTED_IMAGE_MESSAGE.takeIf { inspect != null && isProtectedImage(before.image) },
                pull = mode == EditorMode.New,
                registries = registries,
                registryId = matchFor(before.image, registries),
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
        if (s.phase != EditorPhase.Ready || s.readOnly) return@update s
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
        if (next.type == MountType.Tmpfs) next = next.copy(source = "")
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
        val entry = if (value.isEmpty() && '=' !in old) key else "$key=$value"
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

    fun setStartAfterCreate(on: Boolean) = _state.update { if (it.mode == EditorMode.New) it.copy(startAfterCreate = on) else it }

    // ---- for the finish flows ----

    /** MAC addresses the user typed: those that differ from the loaded value of the same network (Copy keeps only these). */
    fun typedMacs(): Set<String> = typed { row, old -> listOf(row.mac).filter { it.isNotEmpty() && it != old?.mac } }

    /** Network aliases the user added: those not on the loaded network of the same name (Copy keeps only these). */
    fun typedAliases(): Set<String> = typed { row, old -> row.aliases.filter { it !in old?.aliases.orEmpty() } }

    private fun typed(pick: (NetworkRow, NetworkRow?) -> List<String>): Set<String> {
        val s = _state.value
        val before = (listOfNotNull(s.before.primaryNetwork) + s.before.extraNetworks).associateBy { it.name }
        return (listOfNotNull(s.form.primaryNetwork) + s.form.extraNetworks).flatMap { pick(it, before[it.name]) }.toSet()
    }

    /**
     * "Replace" / "Deploy as copy" / "Create": validates and returns whether [action] may go ahead. With errors,
     * every section's errors are shown. Portainer's own container skips validation for a copy: its form can't
     * be edited, so there is nothing the user could fix.
     *
     * Hand-over point for the finish flows (review step, conflict checks, progress): they start here on `true`.
     */
    fun review(action: FinishAction): Boolean {
        val s = _state.value
        val allowed = when (action) {
            FinishAction.Replace -> s.canReplace
            FinishAction.Copy -> s.canCopy
            FinishAction.Create -> s.canCreate
        }
        if (!allowed) return false
        if (s.readOnly) return true
        if (s.errors.isNotEmpty()) {
            _state.update { it.copy(showErrors = true) }
            return false
        }
        return true
    }
}
