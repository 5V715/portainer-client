package dev.silas.portainer.app.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.silas.portainer.app.AppContainer
import dev.silas.portainer.app.support.failureMessage
import dev.silas.portainer.core.api.inspectContainer
import dev.silas.portainer.core.model.COMPOSE_PROJECT_LABEL
import dev.silas.portainer.core.recreate.isProtectedImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** What the Overview tab shows, parsed from the raw Docker inspect document. */
data class ContainerOverview(
    val name: String,
    /** `Config.Image`: the reference the container was created from (also what protection checks use). */
    val image: String,
    val state: String,
    val health: String?,
    val created: String?,
    /** Null when the container never started. */
    val startedAt: String?,
    val ports: List<String>,
    val mounts: List<String>,
    val networks: List<String>,
    val env: List<String>,
    val labels: Map<String, String>,
    val restartPolicy: String,
    val stack: String?,
    val tty: Boolean,
) {
    val protectedImage: Boolean get() = isProtectedImage(image)
}

data class ContainerDetailState(
    val overview: ContainerOverview? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class ContainerDetailViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val endpointId: Int,
    private val containerId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ContainerDetailState())
    val state: StateFlow<ContainerDetailState> = _state

    private var loadJob: Job? = null

    init {
        refresh()
    }

    /** Starts a load, cancelling any load still in flight so an older response can't overwrite newer data. */
    fun refresh() {
        _state.update { it.copy(loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun load() {
        val message = try {
            val overview = parseOverview(container.client(serverId).inspectContainer(endpointId, containerId))
            _state.update { it.copy(overview = overview) }
            null
        } catch (e: Exception) {
            failureMessage(e)
        }
        _state.update { it.copy(loading = false, error = message) }
    }
}

internal fun parseOverview(inspect: JsonObject): ContainerOverview {
    val config = inspect.obj("Config")
    val state = inspect.obj("State")
    val network = inspect.obj("NetworkSettings")
    val labels = config.obj("Labels").mapNotNull { (k, v) -> v.str()?.let { k to it } }.toMap()
    val policy = inspect.obj("HostConfig").obj("RestartPolicy").str("Name").orEmpty()
    return ContainerOverview(
        name = inspect.str("Name").orEmpty().removePrefix("/"),
        image = config.str("Image").orEmpty(),
        state = state.str("Status").orEmpty(),
        health = state.obj("Health").str("Status"),
        created = inspect.str("Created")?.let(::displayTime),
        startedAt = state.str("StartedAt")?.takeUnless { it.startsWith("0001-") }?.let(::displayTime),
        ports = parsePorts(network.obj("Ports"), config.obj("ExposedPorts")),
        mounts = (inspect["Mounts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::mountLine) },
        networks = network.obj("Networks").map { (name, v) ->
            val ip = (v as? JsonObject).str("IPAddress").orEmpty()
            if (ip.isEmpty()) name else "$name $ip"
        },
        env = (config["Env"] as? JsonArray).orEmpty().mapNotNull { it.str() },
        labels = labels,
        restartPolicy = policy.ifEmpty { "no" },
        stack = labels[COMPOSE_PROJECT_LABEL],
        tty = (config["Tty"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

/** Published ports as `80/tcp → 0.0.0.0:8080`; exposed but unpublished ones as `80/tcp`. */
private fun parsePorts(bindings: JsonObject, exposed: JsonObject): List<String> {
    val lines = mutableListOf<String>()
    for ((port, value) in bindings) {
        val published = (value as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (published.isEmpty()) lines += port
        for (b in published) {
            val host = b.str("HostIp").orEmpty().ifEmpty { "0.0.0.0" }
            lines += "$port → $host:${b.str("HostPort").orEmpty()}"
        }
    }
    // A stopped container has no NetworkSettings.Ports; fall back to what the image exposes.
    for (port in exposed.keys) if (bindings[port] == null) lines += port
    return lines
}

private fun mountLine(m: JsonObject): String {
    val type = m.str("Type").orEmpty()
    val source = if (type == "volume") m.str("Name") ?: m.str("Source") else m.str("Source")
    val mode = if ((m["RW"] as? JsonPrimitive)?.booleanOrNull == false) "ro" else "rw"
    return "$type ${source.orEmpty()} → ${m.str("Destination").orEmpty()} ($mode)"
}

/** `2026-10-17T10:00:00.123Z` → `2026-10-17 10:00:00Z`; the zone suffix is kept, fractions dropped. */
private fun displayTime(iso: String): String {
    if (iso.length < 19 || iso[10] != 'T') return iso
    var i = 19
    if (i < iso.length && iso[i] == '.') {
        i++
        while (i < iso.length && iso[i].isDigit()) i++
    }
    return iso.substring(0, 10) + " " + iso.substring(11, 19) + iso.substring(i)
}

private fun JsonObject?.obj(key: String): JsonObject = this?.get(key) as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject?.str(key: String): String? = this?.get(key).str()

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
