package dev.silas.portainer.core.recreate

import dev.silas.portainer.core.model.ImageRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val STACK_LABEL = "com.docker.compose.project"
private const val SHORT_ID_LENGTH = 12

/** Single-value Config fields dropped when equal to the old image's (Cmd handled separately). */
private val IMAGE_DEFAULT_FIELDS = listOf("Entrypoint", "WorkingDir", "User", "Healthcheck", "StopSignal")

/** Config fields Docker rejects or mis-applies when the container shares another container's network namespace. */
private val CONTAINER_MODE_STRIPPED = listOf("Hostname", "Domainname", "MacAddress", "ExposedPorts")

/** EndpointSettings fields that are user configuration; everything else in inspect is runtime state. */
private val ENDPOINT_FIELDS = listOf("IPAMConfig", "Links", "Aliases", "DriverOpts")

/**
 * Turns `docker inspect` output plus the user's edits into a `POST /containers/create` body.
 *
 * Works on raw JSON throughout: every field not explicitly handled here is passed through
 * unchanged, so settings this app doesn't know about survive the recreate.
 */
object RecreatePlanner {
    /**
     * @param oldImageConfig `GET /images/{id}/json` of the container's current image, either the whole
     *   response or just its `Config` object (detected by the presence of a `"Config"` object key).
     *   `null` when the image can no longer be inspected: nothing is stripped and a warning is added.
     */
    fun plan(inspect: JsonObject, oldImageConfig: JsonObject?, edits: RecreateEdits): RecreatePlan {
        val id = inspect.string("Id").orEmpty()
        val shortId = id.take(SHORT_ID_LENGTH)
        val originalConfig = inspect.obj("Config") ?: JsonObject(emptyMap())
        val originalImage = originalConfig.string("Image").orEmpty()
        val hostConfig = inspect.obj("HostConfig") ?: JsonObject(emptyMap())
        val networkMode = hostConfig.string("NetworkMode").orEmpty()

        val config = originalConfig.toMutableMap()
        if (shortId.isNotEmpty() && config.string("Hostname") == shortId) config.remove("Hostname")
        config["Image"] = JsonPrimitive(edits.image)
        config["Env"] = JsonArray(edits.env.map { JsonPrimitive(it) })

        val imageConfig = oldImageConfig?.let { it.obj("Config") ?: it }
        val dropped = if (imageConfig != null) stripImageDefaults(config, imageConfig) else emptyList()

        val isolatedNetwork = networkMode == "host" || networkMode == "none" || networkMode.startsWith("container:")
        if (networkMode.startsWith("container:")) CONTAINER_MODE_STRIPPED.forEach { config.remove(it) }

        config["HostConfig"] = withAnonymousVolumes(hostConfig, inspect.array("Mounts"))
        var extraNetworks = emptyMap<String, JsonObject>()
        if (!isolatedNetwork) {
            val (primary, extra) = splitNetworks(networkMode, inspect.obj("NetworkSettings")?.obj("Networks"), shortId)
            config["NetworkingConfig"] = JsonObject(mapOf("EndpointsConfig" to JsonObject(primary)))
            extraNetworks = extra
        }

        val warnings = buildList {
            originalConfig.obj("Labels")?.string(STACK_LABEL)?.let { add(RecreateWarning.StackMember(it)) }
            if (hostConfig.boolean("AutoRemove")) add(RecreateWarning.AutoRemove)
            if (imageConfig == null) add(RecreateWarning.ImageConfigUnavailable)
            add(RecreateWarning.ConnectionDependency)
        }

        return RecreatePlan(
            containerName = inspect.string("Name").orEmpty().removePrefix("/"),
            wasRunning = inspect.obj("State")?.boolean("Running") == true,
            createBody = JsonObject(config),
            extraNetworks = extraNetworks,
            image = ImageRef.parse(edits.image),
            pull = edits.pullImage || edits.image != originalImage,
            registryId = edits.registryId,
            changes = changeSummary(originalImage, edits, originalConfig.array("Env").strings(), dropped),
            warnings = warnings,
        )
    }

    /** Removes values identical to the old image's config from [config]; returns what was removed. */
    private fun stripImageDefaults(config: MutableMap<String, JsonElement>, image: JsonObject): List<String> {
        val dropped = mutableListOf<String>()

        val imageEnv = image.array("Env").strings().toSet()
        val env = config.array("Env").strings()
        val (inherited, kept) = env.partition { it in imageEnv }
        inherited.forEach { dropped += "Env $it" }
        config["Env"] = JsonArray(kept.map { JsonPrimitive(it) })

        for (field in IMAGE_DEFAULT_FIELDS) {
            if (dropIfEqual(config, image, field)) dropped += field
        }
        // Docker inherits the image Cmd only when the create request has no Entrypoint;
        // dropping Cmd next to a user Entrypoint would start the container without its arguments.
        if (config["Entrypoint"].isEmptyJson() && dropIfEqual(config, image, "Cmd")) dropped += "Cmd"

        dropMatchingEntries(config, image, "Labels") { key, value -> "Label $key=${(value as? JsonPrimitive)?.content ?: value}" }
            .let { dropped += it }
        dropMatchingEntries(config, image, "ExposedPorts") { key, _ -> "ExposedPort $key" }.let { dropped += it }
        dropMatchingEntries(config, image, "Volumes") { key, _ -> "Volume $key" }.let { dropped += it }
        return dropped
    }

    /**
     * Removes [field] when it equals the image's value. Returns true only for a meaningful drop:
     * both sides empty (e.g. `User: ""`) is removed silently, as it changes nothing.
     */
    private fun dropIfEqual(config: MutableMap<String, JsonElement>, image: JsonObject, field: String): Boolean {
        if (field !in config) return false
        val value = config.getValue(field)
        val imageValue = image[field] ?: JsonNull
        val bothEmpty = value.isEmptyJson() && imageValue.isEmptyJson()
        if (value != imageValue && !bothEmpty) return false
        config.remove(field)
        return !bothEmpty
    }

    /** Removes map entries of [field] whose key and value both match the image's. */
    private fun dropMatchingEntries(
        config: MutableMap<String, JsonElement>,
        image: JsonObject,
        field: String,
        describe: (String, JsonElement) -> String,
    ): List<String> {
        val entries = config[field] as? JsonObject ?: return emptyList()
        val imageEntries = image.obj(field) ?: return emptyList()
        val (inherited, kept) = entries.entries.partition { (k, v) -> imageEntries[k] == v }
        if (inherited.isEmpty()) return emptyList()
        config[field] = JsonObject(kept.associate { it.key to it.value })
        return inherited.map { describe(it.key, it.value) }
    }

    /**
     * Appends every `volume` mount not already declared in `HostConfig.Binds`/`HostConfig.Mounts`
     * (i.e. anonymous volumes) to `Binds` by name, so the new container reattaches the same data
     * instead of getting a fresh empty volume.
     */
    private fun withAnonymousVolumes(hostConfig: JsonObject, mounts: JsonArray?): JsonObject {
        val binds = hostConfig.array("Binds").strings()
        val covered = binds.map(::bindTarget).toMutableSet()
        hostConfig.array("Mounts")?.forEach { m -> (m as? JsonObject)?.string("Target")?.let { covered += it } }

        val added = mounts.orEmpty().mapNotNull { m ->
            val mount = m as? JsonObject ?: return@mapNotNull null
            val name = mount.string("Name")
            val destination = mount.string("Destination")
            if (mount.string("Type") != "volume" || name.isNullOrEmpty() || destination.isNullOrEmpty()) return@mapNotNull null
            if (!covered.add(destination)) return@mapNotNull null
            if (mount["RW"] == JsonPrimitive(false)) "$name:$destination:ro" else "$name:$destination"
        }
        if (added.isEmpty()) return hostConfig
        return JsonObject(hostConfig + ("Binds" to JsonArray((binds + added).map { JsonPrimitive(it) })))
    }

    /** Container path of a `src:dst[:opts]` bind (a lone path is its own target). */
    private fun bindTarget(bind: String): String = bind.split(':').let { if (it.size >= 2) it[1] else it[0] }

    /** Picks the network for `NetworkingConfig` (named by [networkMode], else the first); the rest are connected later. */
    private fun splitNetworks(
        networkMode: String,
        networks: JsonObject?,
        shortId: String,
    ): Pair<Map<String, JsonObject>, Map<String, JsonObject>> {
        if (networks == null || networks.isEmpty()) return emptyMap<String, JsonObject>() to emptyMap()
        val wanted = if (networkMode == "default") "bridge" else networkMode
        val primaryName = networks.keys.firstOrNull { it == wanted }
            ?: networks.entries.firstOrNull { (it.value as? JsonObject)?.string("NetworkID") == wanted }?.key
            ?: networks.keys.first()
        val configs = networks.mapValues { (_, v) -> endpointConfig(v as? JsonObject ?: JsonObject(emptyMap()), shortId) }
        return mapOf(primaryName to configs.getValue(primaryName)) to (configs - primaryName)
    }

    /** User-settable part of an endpoint: IPAM config, links, aliases (minus Docker's short-id alias), driver opts. */
    private fun endpointConfig(endpoint: JsonObject, shortId: String): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        for (field in ENDPOINT_FIELDS) {
            val value = endpoint[field] ?: continue
            if (value is JsonNull) continue
            if (field == "Aliases") {
                val aliases = (value as? JsonArray)?.filterNot { (it as? JsonPrimitive)?.content == shortId }.orEmpty()
                if (aliases.isNotEmpty()) out[field] = JsonArray(aliases)
            } else {
                out[field] = value
            }
        }
        return JsonObject(out)
    }

    /** Env diff is by key (text before the first `=`); [oldEnv] is the container's `Config.Env` as inspected. */
    private fun changeSummary(oldImage: String, edits: RecreateEdits, oldEnv: List<String>, dropped: List<String>): ChangeSummary {
        val new = edits.env
        val oldByKey = oldEnv.associateBy(::envKey)
        val newByKey = new.associateBy(::envKey)
        return ChangeSummary(
            imageChange = if (edits.image != oldImage) oldImage to edits.image else null,
            envAdded = new.filter { envKey(it) !in oldByKey },
            envChanged = newByKey.mapNotNull { (k, entry) -> oldByKey[k]?.takeIf { it != entry }?.let { it to entry } },
            envRemoved = oldEnv.filter { envKey(it) !in newByKey },
            droppedImageDefaults = dropped,
        )
    }

    private fun envKey(entry: String) = entry.substringBefore('=')
}

private fun Map<String, JsonElement>.obj(key: String) = this[key] as? JsonObject
private fun Map<String, JsonElement>.array(key: String) = this[key] as? JsonArray
private fun Map<String, JsonElement>.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun Map<String, JsonElement>.boolean(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
private fun JsonArray?.strings() = orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

private fun JsonElement?.isEmptyJson(): Boolean = when (this) {
    null, JsonNull -> true
    is JsonArray -> isEmpty()
    is JsonObject -> isEmpty()
    is JsonPrimitive -> isString && content.isEmpty()
}
