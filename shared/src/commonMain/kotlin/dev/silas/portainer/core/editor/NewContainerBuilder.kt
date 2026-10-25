package dev.silas.portainer.core.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a `POST /containers/create` body for a new container from the form alone: no planner and no
 * image-default stripping, so anything the form leaves empty takes the image's or Docker's default.
 */
object NewContainerBuilder {
    /** @return the container name and the create body. Extra networks are connected after create ([extraNetworks]). */
    fun build(form: ContainerForm): Pair<String, JsonObject> {
        val body = linkedMapOf<String, JsonElement>()
        body["Image"] = JsonPrimitive(form.image)
        body["Env"] = stringArray(form.env)
        body["Labels"] = JsonObject(form.labels.associate { (k, v) -> k to JsonPrimitive(v) })
        form.command?.let { body["Cmd"] = stringArray(it) }
        form.entrypoint?.let { body["Entrypoint"] = stringArray(it) }
        if (form.workingDir.isNotEmpty()) body["WorkingDir"] = JsonPrimitive(form.workingDir)
        if (form.user.isNotEmpty()) body["User"] = JsonPrimitive(form.user)

        val host = linkedMapOf<String, JsonElement>()
        host["RestartPolicy"] = JsonObject(
            mapOf(
                "Name" to JsonPrimitive(form.restart.name),
                // Docker only accepts a retry count with on-failure.
                "MaximumRetryCount" to JsonPrimitive(if (form.restart.name == "on-failure") form.restart.maxRetries else 0),
            ),
        )

        val ports = form.ports.groupBy { "${it.containerPort}/${it.protocol}" }
        if (ports.isNotEmpty()) {
            body["ExposedPorts"] = JsonObject(ports.mapValues { JsonObject(emptyMap()) })
            host["PortBindings"] = JsonObject(
                ports.mapValues { (_, rows) ->
                    JsonArray(
                        rows.map { JsonObject(mapOf("HostIp" to JsonPrimitive(it.hostIp), "HostPort" to JsonPrimitive(it.hostPort))) },
                    )
                },
            )
        }
        if (form.publishAll) host["PublishAllPorts"] = JsonPrimitive(true)

        // Bind and volume rows as `-v` strings (Docker creates a missing host path), tmpfs as Mounts entries.
        val (tmpfs, binds) = form.mounts.partition { it.type == MountType.Tmpfs }
        if (binds.isNotEmpty()) host["Binds"] = stringArray(binds.map { FormMerger.bindString(it, null) })
        if (tmpfs.isNotEmpty()) host["Mounts"] = JsonArray(tmpfs.map { FormMerger.mountObject(it, null) })

        form.memoryBytes?.let { host["Memory"] = JsonPrimitive(it) }
        form.memoryReservationBytes?.let { host["MemoryReservation"] = JsonPrimitive(it) }
        form.nanoCpus?.let { host["NanoCpus"] = JsonPrimitive(it) }
        form.cpuShares?.let { host["CpuShares"] = JsonPrimitive(it) }

        val primary = form.primaryNetwork.takeIf { form.networkModeReadOnly == null }
        val networkMode = form.networkModeReadOnly ?: primary?.name
        if (networkMode != null) host["NetworkMode"] = JsonPrimitive(networkMode)
        body["HostConfig"] = JsonObject(host)
        if (primary != null) {
            body["NetworkingConfig"] = JsonObject(mapOf("EndpointsConfig" to JsonObject(mapOf(primary.name to endpointConfig(primary)))))
        }
        return form.name to JsonObject(body)
    }

    /** Networks to connect after create (`POST /networks/{name}/connect`): name → `EndpointConfig`. */
    fun extraNetworks(form: ContainerForm): Map<String, JsonObject> =
        if (form.networkModeReadOnly != null) emptyMap() else form.extraNetworks.associate { it.name to endpointConfig(it) }

    /** Only what the user set: aliases, static IPs, MAC. */
    private fun endpointConfig(row: NetworkRow): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        if (row.aliases.isNotEmpty()) out["Aliases"] = stringArray(row.aliases)
        val ipam = linkedMapOf<String, JsonElement>()
        if (row.ipv4.isNotEmpty()) ipam["IPv4Address"] = JsonPrimitive(row.ipv4)
        if (row.ipv6.isNotEmpty()) ipam["IPv6Address"] = JsonPrimitive(row.ipv6)
        if (ipam.isNotEmpty()) out["IPAMConfig"] = JsonObject(ipam)
        if (row.mac.isNotEmpty()) out["MacAddress"] = JsonPrimitive(row.mac)
        return JsonObject(out)
    }
}
