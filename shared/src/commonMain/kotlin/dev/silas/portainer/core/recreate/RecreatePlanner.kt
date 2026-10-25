package dev.silas.portainer.core.recreate

import dev.silas.portainer.core.editor.parseBind
import dev.silas.portainer.core.model.COMPOSE_PROJECT_LABEL
import dev.silas.portainer.core.model.ImageRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val SHORT_ID_LENGTH = 12

/** Single-value Config fields dropped when equal to the old image's (Cmd handled separately). */
private val IMAGE_DEFAULT_FIELDS = listOf("Entrypoint", "WorkingDir", "User", "Healthcheck", "StopSignal")

/** Config fields Docker rejects or mis-applies when the container shares another container's network namespace. */
private val CONTAINER_MODE_STRIPPED = listOf("Hostname", "Domainname", "MacAddress", "ExposedPorts")

/**
 * EndpointSettings fields that are user configuration; everything else in inspect is runtime state.
 * `MacAddress` is kept so a pinned MAC survives (reusing a generated one is harmless: the old
 * container is stopped before the new one starts).
 */
private val ENDPOINT_FIELDS = listOf("IPAMConfig", "Links", "Aliases", "DriverOpts", "MacAddress", "GwPriority")

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

        var newHostConfig = withAnonymousVolumes(hostConfig, inspect.array("Mounts"))
        var extraNetworks = emptyMap<String, JsonObject>()
        if (!isolatedNetwork) {
            val split = splitNetworks(networkMode, inspect.obj("NetworkSettings")?.obj("Networks"), shortId)
            config["NetworkingConfig"] = JsonObject(mapOf("EndpointsConfig" to JsonObject(split.primary)))
            // Docker API >= 1.44 rejects a create whose top-level MacAddress differs from the primary endpoint's
            // (e.g. after falling back to another network); the endpoint's value is the one Docker applies.
            if (split.primary.values.any { it.containsKey("MacAddress") }) config.remove("MacAddress")
            extraNetworks = split.extra
            // NetworkMode names a network the container isn't on: point it at the network we attach to.
            if (split.fellBack) newHostConfig = JsonObject(newHostConfig + ("NetworkMode" to JsonPrimitive(split.primary.keys.single())))
        }
        config["HostConfig"] = newHostConfig

        val warnings = buildList {
            originalConfig.obj("Labels")?.string(COMPOSE_PROJECT_LABEL)?.let { add(RecreateWarning.StackMember(it)) }
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
            changes = changeSummary(originalImage, edits, originalConfig.array("Env").strings(), imageConfig, dropped),
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
     * Makes every volume in the inspect `Mounts[]` reattach by name, so the new container gets the same
     * data instead of a fresh empty volume:
     * - `HostConfig.Mounts` volume entries without a `Source` (`--mount type=volume,target=…`, compose
     *   anonymous volumes) get `Source` = the volume's name;
     * - anonymous `Binds` entries (`/data`, `/data:ro`) become `<name>:/data[:mode]`;
     * - volumes declared nowhere in `HostConfig` (image `VOLUME`, `-v /data`) are appended to `Binds`.
     * Nothing is ever added for a target already present, since Docker rejects duplicate mount points.
     */
    private fun withAnonymousVolumes(hostConfig: JsonObject, mounts: JsonArray?): JsonObject {
        val volumes = linkedMapOf<String, JsonObject>()
        mounts.orEmpty().forEach { m ->
            val mount = m as? JsonObject ?: return@forEach
            val destination = mount.string("Destination")
            if (mount.string("Type") == "volume" && !mount.string("Name").isNullOrEmpty() && !destination.isNullOrEmpty()) {
                volumes.getOrPut(destination) { mount }
            }
        }
        if (volumes.isEmpty()) return hostConfig
        val covered = mutableSetOf<String>()
        val result = hostConfig.toMutableMap()

        val originalBinds = hostConfig.array("Binds").strings()
        val binds = originalBinds.map { bind ->
            val spec = parseBind(bind)
            covered += spec.target
            val volume = volumes[spec.target]
            if (spec.source == null && volume != null) {
                volumeBind(volume.string("Name")!!, spec.target, spec.options.joinToString(",").ifEmpty { null } ?: mountOptions(volume))
            } else {
                bind
            }
        }

        val hostMounts = hostConfig.array("Mounts")?.map { m ->
            val mount = m as? JsonObject ?: return@map m
            val target = mount.string("Target") ?: return@map m
            covered += target
            val volume = volumes[target]
            if (mount.string("Type") == "volume" && mount.string("Source").isNullOrEmpty() && volume != null) {
                JsonObject(mount + ("Source" to JsonPrimitive(volume.string("Name")!!)))
            } else {
                mount
            }
        }
        if (hostMounts != null) result["Mounts"] = JsonArray(hostMounts)

        val added = volumes.filterKeys { it !in covered }.map { (destination, volume) ->
            volumeBind(volume.string("Name")!!, destination, mountOptions(volume))
        }
        if (binds != originalBinds || added.isNotEmpty()) result["Binds"] = JsonArray((binds + added).map { JsonPrimitive(it) })
        return JsonObject(result)
    }

    /** `-v` options for a reattached volume: `ro` when read-only, plus the mount's `Mode` (e.g. `z`, `nocopy`). */
    private fun mountOptions(volume: JsonObject): String? {
        val options = mutableListOf<String>()
        if (volume["RW"] == JsonPrimitive(false)) options += "ro"
        volume.string("Mode")?.split(',')?.filter { it.isNotEmpty() && it !in options }?.let { options += it }
        return options.joinToString(",").ifEmpty { null }
    }

    private fun volumeBind(name: String, destination: String, mode: String?) =
        if (mode == null) "$name:$destination" else "$name:$destination:$mode"

    private class NetworkSplit(val primary: Map<String, JsonObject>, val extra: Map<String, JsonObject>, val fellBack: Boolean)

    /**
     * Picks the network for `NetworkingConfig` (named by [networkMode] or matching its NetworkID; `default`
     * means `bridge`), else falls back to the first one; the rest are connected after create.
     */
    private fun splitNetworks(networkMode: String, networks: JsonObject?, shortId: String): NetworkSplit {
        if (networks == null || networks.isEmpty()) return NetworkSplit(emptyMap(), emptyMap(), fellBack = false)
        val wanted = if (networkMode == "default") "bridge" else networkMode
        val matched = networks.keys.firstOrNull { it == wanted }
            ?: networks.entries.firstOrNull { (it.value as? JsonObject)?.string("NetworkID") == wanted }?.key
        val primaryName = matched ?: networks.keys.first()
        val configs = networks.mapValues { (_, v) -> endpointConfig(v as? JsonObject ?: JsonObject(emptyMap()), shortId) }
        return NetworkSplit(mapOf(primaryName to configs.getValue(primaryName)), configs - primaryName, fellBack = matched == null)
    }

    /** User-settable part of an endpoint ([ENDPOINT_FIELDS]); Docker's short-id alias and empty values are left out. */
    private fun endpointConfig(endpoint: JsonObject, shortId: String): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        for (field in ENDPOINT_FIELDS) {
            val value = endpoint[field] ?: continue
            if (value.isEmptyJson()) continue
            if (field == "Aliases") {
                val aliases = (value as? JsonArray)?.filterNot { (it as? JsonPrimitive)?.content == shortId }.orEmpty()
                if (aliases.isNotEmpty()) out[field] = JsonArray(aliases)
            } else {
                out[field] = value
            }
        }
        return JsonObject(out)
    }

    /**
     * Env diff is by key (text before the first `=`); [oldEnv] is the container's `Config.Env` as inspected.
     * Docker merges the image's Env at create, so a removed key the old image defines comes back with the
     * image's value: those image entries are listed in `envReinherited`.
     */
    private fun changeSummary(
        oldImage: String,
        edits: RecreateEdits,
        oldEnv: List<String>,
        imageConfig: JsonObject?,
        dropped: List<String>,
    ): ChangeSummary {
        val new = edits.env
        val oldByKey = oldEnv.associateBy(::envKey)
        val newByKey = new.associateBy(::envKey)
        return ChangeSummary(
            imageChange = if (edits.image != oldImage) oldImage to edits.image else null,
            envAdded = new.filter { envKey(it) !in oldByKey },
            envChanged = newByKey.mapNotNull { (k, entry) -> oldByKey[k]?.takeIf { it != entry }?.let { it to entry } },
            envRemoved = oldEnv.filter { envKey(it) !in newByKey },
            droppedImageDefaults = dropped,
            envReinherited = imageConfig?.array("Env").strings().filter { envKey(it) in oldByKey && envKey(it) !in newByKey },
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
