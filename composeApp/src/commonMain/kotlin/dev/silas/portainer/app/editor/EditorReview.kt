package dev.silas.portainer.app.editor

import dev.silas.portainer.app.recreate.summaryLines
import dev.silas.portainer.app.recreate.warningText
import dev.silas.portainer.core.api.PortainerClient
import dev.silas.portainer.core.api.containers
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.api.inspectNetwork
import dev.silas.portainer.core.editor.Conflict
import dev.silas.portainer.core.editor.ConflictChecker
import dev.silas.portainer.core.editor.ContainerForm
import dev.silas.portainer.core.editor.FormField
import dev.silas.portainer.core.editor.FormMerger
import dev.silas.portainer.core.editor.FormParser
import dev.silas.portainer.core.editor.NewContainerBuilder
import dev.silas.portainer.core.editor.blocking
import dev.silas.portainer.core.editor.describeChanges
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.model.ImageRef
import dev.silas.portainer.core.model.Network
import dev.silas.portainer.core.model.PublishedPort
import dev.silas.portainer.core.recreate.PlanMode
import dev.silas.portainer.core.recreate.PlanOptions
import dev.silas.portainer.core.recreate.RecreateEdits
import dev.silas.portainer.core.recreate.RecreatePlan
import dev.silas.portainer.core.recreate.RecreatePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val CONTAINER_CHANGED_NOTICE =
    "The container changed since you opened the editor — the summary reflects its current settings."

private const val MAX_PORT = 65535

/** The finish step after the form: checking, the review, then a deploy's progress (Replace hands over to RecreateSessions). */
sealed interface FinishPhase {
    data class Checking(val action: FinishAction) : FinishPhase

    /**
     * What will happen. [name] is the container's name ([nameEditable] for a copy); [changes] the change summary
     * (Replace / Copy against the container's current settings, Create: what is set); [warnings] plan warnings and
     * non-blocking conflicts; [blocked] a reason the action can't go ahead at all.
     */
    data class Review(
        val action: FinishAction,
        val name: String,
        val nameEditable: Boolean,
        val nameError: String?,
        val changes: List<String>,
        val warnings: List<String>,
        val conflicts: List<Conflict>,
        val blocking: List<Conflict>,
        val blocked: String?,
        val notice: String?,
        /** Copy / Create: whether the new container is started. */
        val start: Boolean,
        val starting: Boolean = false,
    ) : FinishPhase {
        val canConfirm get() = blocked == null && nameError == null && blocking.isEmpty() && !starting
    }

    /** Checking failed (e.g. the container is gone); the user goes back to the form. */
    data class Failed(val action: FinishAction, val message: String) : FinishPhase

    data class Deploying(val progress: DeployProgress) : FinishPhase
}

/** One-shot navigation the screen performs, then reports with [EditorViewModel.navigated]. */
sealed interface EditorNavigation {
    /** The Replace runs in RecreateSessions: show its progress. */
    data object ReplaceStarted : EditorNavigation

    data class OpenContainer(val id: String) : EditorNavigation
}

fun conflictText(c: Conflict): String = when (c) {
    is Conflict.NameTaken -> "The name ${c.name} is already used by another container. Choose another name."
    is Conflict.HostPortInUse ->
        if (c.running) "Host port ${c.port}/${c.protocol} is in use by running container ${c.byContainer}."
        else "Host port ${c.port}/${c.protocol} is also published by stopped container ${c.byContainer}; only one of them can run at a time."
    is Conflict.FixedIpInUse -> "IP ${c.ip} on network ${c.network} is already used by container ${c.byContainer}."
    is Conflict.SharedNamedVolume -> "Both containers will use volume ${c.volume}."
}

/** What [ConflictChecker] needs from the endpoint, gathered once per review. */
internal class ConflictInputs(
    val containers: List<ContainerSummary>,
    val networks: List<Network>,
    val stoppedPortBindings: Map<String, List<PublishedPort>>,
) {
    fun check(body: JsonObject, extraNetworks: Map<String, JsonObject>, name: String, excludeContainerId: String?): List<Conflict> =
        ConflictChecker.check(withExtras(body, extraNetworks), name, containers, networks, excludeContainerId, stoppedPortBindings)
}

/**
 * Lists the containers, inspects the stopped ones (the list only reports running containers' ports) and the
 * networks [networkNames] that get a fixed IP (the network list leaves their members out). An inspect that
 * fails is skipped: the check then knows less, and create would still fail on a real clash.
 */
internal suspend fun conflictInputs(client: PortainerClient, endpointId: Int, networkNames: Set<String>): ConflictInputs = coroutineScope {
    val containers = client.containers(endpointId)
    val stopped = containers.filter { it.state != ContainerState.Running && it.state != ContainerState.Paused }.map { c ->
        async { optional { c.name to portBindings(client.inspectContainer(endpointId, c.id)) } }
    }
    val networks = networkNames.map { n -> async { optional { client.inspectNetwork(endpointId, n) } } }
    ConflictInputs(
        containers,
        networks.awaitAll().filterNotNull(),
        stopped.awaitAll().filterNotNull().filter { it.second.isNotEmpty() }.toMap(),
    )
}

/** Networks of a create body (plus [extraNetworks]) whose endpoint asks for a fixed IP. */
internal fun fixedIpNetworks(body: JsonObject, extraNetworks: Map<String, JsonObject>): Set<String> =
    endpoints(withExtras(body, extraNetworks)).filter { (_, e) ->
        val ipam = (e as? JsonObject)?.get("IPAMConfig") as? JsonObject
        listOf("IPv4Address", "IPv6Address").any { ((ipam?.get(it) as? JsonPrimitive)?.content).orEmpty().isNotEmpty() }
    }.keys

private suspend fun <T> optional(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private fun endpoints(body: JsonObject): JsonObject =
    ((body["NetworkingConfig"] as? JsonObject)?.get("EndpointsConfig") as? JsonObject) ?: JsonObject(emptyMap())

/** The checker reads `NetworkingConfig.EndpointsConfig`; networks connected after create are checked the same way. */
private fun withExtras(body: JsonObject, extraNetworks: Map<String, JsonObject>): JsonObject {
    if (extraNetworks.isEmpty()) return body
    val all = JsonObject(endpoints(body) + extraNetworks)
    return JsonObject(body + ("NetworkingConfig" to JsonObject(mapOf("EndpointsConfig" to all))))
}

/** `HostConfig.PortBindings` of an inspect as published ports (ranges expanded; a random host port has none). */
internal fun portBindings(inspect: JsonObject): List<PublishedPort> {
    val bindings = (inspect["HostConfig"] as? JsonObject)?.get("PortBindings") as? JsonObject ?: return emptyList()
    return bindings.flatMap { (key, value) ->
        val privatePort = key.substringBefore('/').toIntOrNull() ?: 0
        val protocol = key.substringAfter('/', "tcp")
        (value as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.flatMap { b ->
            val ip = (b["HostIp"] as? JsonPrimitive)?.content.orEmpty()
            hostPorts((b["HostPort"] as? JsonPrimitive)?.content.orEmpty()).map { PublishedPort(ip, it, privatePort, protocol) }
        }
    }
}

private fun hostPorts(spec: String): List<Int> {
    val t = spec.trim()
    t.toIntOrNull()?.let { return listOf(it) }
    val start = t.substringBefore('-').toIntOrNull()
    val end = t.substringAfter('-', "").toIntOrNull()
    return if (start != null && end != null && start in 1..end && end <= MAX_PORT) (start..end).toList() else emptyList()
}

/** Fetched once per review; the review is rebuilt from it (no requests) when the copy's name changes. */
internal class ReviewContext(
    val action: FinishAction,
    /** Replace / Copy: the container's inspect at review time, its image's config and its form. */
    val fresh: JsonObject?,
    val imageConfig: JsonObject?,
    val freshBefore: ContainerForm?,
    val inputs: ConflictInputs,
    val notice: String?,
    val blocked: String?,
) {
    fun with(inputs: ConflictInputs) = ReviewContext(action, fresh, imageConfig, freshBefore, inputs, notice, blocked)
}

internal val NO_INPUTS = ConflictInputs(emptyList(), emptyList(), emptyMap())

/** The review plus what confirming it runs: a Replace [plan] or a deploy [request]. */
internal class BuiltReview(val review: FinishPhase.Review, val plan: RecreatePlan?, val request: DeployRequest?)

/**
 * Builds the review for [ctx] from the editor state [s]. Replace / Copy merge the form onto the fresh inspect
 * (`original` = that inspect) and plan it; Create builds the body from the form alone.
 */
internal fun buildReview(ctx: ReviewContext, s: EditorState, name: String, typedMacs: Set<String>, typedAliases: Set<String>): BuiltReview {
    val fresh = ctx.fresh
    if (ctx.action == FinishAction.Create || fresh == null) {
        val form = s.form
        val (_, body) = NewContainerBuilder.build(form)
        val extras = NewContainerBuilder.extraNetworks(form)
        val conflicts = ctx.inputs.check(body, extras, name, excludeContainerId = null)
        val request = DeployRequest(name, body, extras, ImageRef.parse(form.image), s.pull, s.registryId, s.startAfterCreate)
        val review = review(ctx, name, nameEditable = false, newContainerLines(form), emptyList(), conflicts, conflicts.filter { it.blocking }, s.startAfterCreate)
        return BuiltReview(review, null, request)
    }
    val copy = ctx.action == FinishAction.Copy
    val form = if (copy) s.form.copy(name = name) else s.form
    val (merged, fields) = FormMerger.merge(fresh, s.before, form)
    val options = PlanOptions(
        mode = if (copy) PlanMode.Copy else PlanMode.Replace,
        userEdited = fields,
        typedMacs = if (copy) typedMacs else emptySet(),
        typedAliases = if (copy) typedAliases else emptySet(),
        original = fresh,
    )
    val planned = RecreatePlanner.plan(merged, ctx.imageConfig, RecreateEdits(form.image, form.env, s.pull, s.registryId), options)
    val other = ctx.freshBefore?.let { describeChanges(it, FormParser.fromInspect(merged)) }.orEmpty()
    val plan = planned.copy(changes = planned.changes.copy(otherChanges = other))
    val excluded = if (copy) null else (fresh["Id"] as? JsonPrimitive)?.content
    val all = ctx.inputs.check(plan.createBody, plan.extraNetworks, plan.containerName, excluded)
    // Replace: the container already uses its volumes, and a clash only makes the start fail (the original is
    // then restored), so only a name that can't be created blocks.
    val conflicts = if (copy) all else all.filterNot { it is Conflict.SharedNamedVolume }
    val blocking = if (copy) conflicts.filter { it.blocking } else conflicts.filterIsInstance<Conflict.NameTaken>()
    val review = review(ctx, plan.containerName, copy, summaryLines(plan.changes), plan.warnings.map(::warningText), conflicts, blocking, plan.wasRunning)
    val request = if (copy) {
        DeployRequest(plan.containerName, plan.createBody, plan.extraNetworks, plan.image, plan.pull, plan.registryId, start = plan.wasRunning)
    } else {
        null
    }
    return BuiltReview(review, if (copy) null else plan, request)
}

private fun review(
    ctx: ReviewContext,
    name: String,
    nameEditable: Boolean,
    changes: List<String>,
    planWarnings: List<String>,
    conflicts: List<Conflict>,
    blocking: List<Conflict>,
    start: Boolean,
) = FinishPhase.Review(
    action = ctx.action,
    name = name,
    nameEditable = nameEditable,
    nameError = if (nameEditable) containerNameProblem(name) else null,
    changes = changes,
    warnings = planWarnings + (conflicts - blocking.toSet()).map(::conflictText),
    conflicts = conflicts,
    blocking = blocking,
    blocked = ctx.blocked,
    notice = ctx.notice,
    start = start,
)

/** Create: what the new container gets (the image, then each section the form sets). */
private fun newContainerLines(form: ContainerForm): List<String> = buildList {
    add("Image: ${form.image}")
    if (form.name.isNotEmpty()) add("Name: ${form.name}")
    form.env.forEach { add("Variable: $it") }
    addAll(describeChanges(FormParser.empty().copy(name = form.name), form))
}

private val FIELD_LABELS = mapOf(
    FormField.Image to "Image", FormField.Name to "Name", FormField.Command to "Command", FormField.Entrypoint to "Entrypoint",
    FormField.WorkingDir to "Working directory", FormField.User to "User", FormField.Ports to "Ports", FormField.Mounts to "Volumes",
    FormField.Networks to "Networks", FormField.Env to "Environment variables", FormField.Labels to "Labels",
    FormField.Restart to "Restart policy", FormField.Resources to "Resources",
)

/** The user edited [fields] that also changed outside the editor: applying the edits would work on stale settings. */
internal fun staleEditsMessage(fields: Set<FormField>): String =
    "Changed outside the editor since you opened it: ${FormField.entries.filter { it in fields }.joinToString { FIELD_LABELS.getValue(it) }}. " +
        "Go back and open the editor again to edit the container's current settings."
