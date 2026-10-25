package dev.silas.portainer.app.editor

import dev.silas.portainer.core.editor.MountRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.strings() = (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
private fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Settings the form keeps but doesn't edit (devices, capabilities, privileged, log config, healthcheck, DNS,
 * extra hosts), as label → value lines; only the ones that are set.
 */
fun advancedSummary(inspect: JsonObject): List<Pair<String, String>> = buildList {
    val config = inspect["Config"].obj()
    val host = inspect["HostConfig"].obj()
    fun list(label: String, values: List<String>) {
        if (values.isNotEmpty()) add(label to values.joinToString(", "))
    }

    if ((host?.get("Privileged") as? JsonPrimitive)?.content == "true") add("Privileged" to "Yes")
    list(
        "Devices",
        (host?.get("Devices") as? JsonArray).orEmpty().mapNotNull { d ->
            val o = d.obj() ?: return@mapNotNull null
            val from = o["PathOnHost"].text().orEmpty()
            val to = o["PathInContainer"].text().orEmpty().ifEmpty { from }
            "$from → $to"
        },
    )
    list("Capabilities added", host?.get("CapAdd").strings())
    list("Capabilities dropped", host?.get("CapDrop").strings())
    host?.get("LogConfig").obj()?.let { log ->
        val type = log["Type"].text().orEmpty()
        val options = log["Config"].obj()?.size ?: 0
        if (type.isNotEmpty()) add("Log driver" to if (options > 0) "$type ($options options)" else type)
    }
    config?.get("Healthcheck").obj()?.let { check ->
        val test = check["Test"].strings()
        val text = when (test.firstOrNull()) {
            null -> null
            "NONE" -> "Disabled"
            "CMD-SHELL", "CMD" -> test.drop(1).joinToString(" ")
            else -> test.joinToString(" ")
        }
        text?.let { add("Healthcheck" to it) }
    }
    list("DNS servers", host?.get("Dns").strings())
    list("DNS search domains", host?.get("DnsSearch").strings())
    list("Extra hosts", host?.get("ExtraHosts").strings())
}

/**
 * Container paths of volumes that only exist in inspect `Mounts` (an image `VOLUME` Docker created an anonymous
 * volume for) and so are no form row: kept on replace, fresh on copy.
 */
fun anonymousVolumeTargets(inspect: JsonObject, rows: List<MountRow>): List<String> {
    val targets = rows.map { it.target }.toSet()
    return (inspect["Mounts"] as? JsonArray).orEmpty().mapNotNull { m ->
        val o = m.obj() ?: return@mapNotNull null
        val target = o["Destination"].text() ?: return@mapNotNull null
        target.takeIf { o["Type"].text() == "volume" && it !in targets }
    }
}
