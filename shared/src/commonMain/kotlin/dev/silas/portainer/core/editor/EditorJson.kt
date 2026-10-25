package dev.silas.portainer.core.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lenient reads of inspect JSON shared by [FormParser] and [FormMerger]: wrong types read as absent. */
internal fun Map<String, JsonElement>.obj(key: String) = this[key] as? JsonObject
internal fun Map<String, JsonElement>.array(key: String) = this[key] as? JsonArray
internal fun Map<String, JsonElement>.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun Map<String, JsonElement>.boolean(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
internal fun Map<String, JsonElement>.long(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
internal fun JsonArray?.strings() = orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

internal fun stringArray(values: List<String>) = JsonArray(values.map { JsonPrimitive(it) })

/** Docker generates the 12-character short id as hostname and network alias. */
internal const val SHORT_ID_LENGTH = 12

/** Network modes the form shows read-only. */
internal fun isReadOnlyNetworkMode(mode: String) = mode == "host" || mode == "none" || mode.startsWith("container:")

/** Option words Docker accepts in the mode part of a `-v` spec (`[src:]dst[:mode]`). */
private val BIND_MODE_WORDS = setOf(
    "ro", "rw", "z", "Z", "nocopy", "shared", "rshared", "slave", "rslave", "private", "rprivate",
    "consistent", "cached", "delegated",
)

/** A parsed `-v` spec; [options] are the comma-separated mode words. */
internal class BindSpec(val source: String?, val target: String, val options: List<String>)

/** True when [s] has a Windows drive path (`c:\…`, `c:/…`) at [index]. */
internal fun isDrivePath(s: String, index: Int = 0): Boolean =
    index + 2 < s.length && (s[index] in 'A'..'Z' || s[index] in 'a'..'z') && s[index + 1] == ':' && (s[index + 2] == '\\' || s[index + 2] == '/')

/** Host paths (as opposed to volume names): absolute Linux paths, Windows drive paths, `\\.\pipe\…` / UNC. */
internal fun isHostPath(source: String) = source.startsWith("/") || isDrivePath(source) || source.startsWith("\\\\")

/** Splits a `-v` spec at `:`, except the colon of a Windows drive letter that starts a segment. */
private fun bindSegments(bind: String): List<String> {
    val segments = mutableListOf<String>()
    var start = 0
    while (true) {
        val from = if (isDrivePath(bind, start)) start + 2 else start
        val colon = bind.indexOf(':', from)
        if (colon < 0) {
            segments += bind.substring(start)
            return segments
        }
        segments += bind.substring(start, colon)
        start = colon + 1
    }
}

/** Parses `dst`, `dst:mode`, `src:dst`, `src:dst:mode` (Linux and Windows paths). The one `-v` grammar of the app. */
internal fun parseBind(bind: String): BindSpec {
    val parts = bindSegments(bind)
    fun opts(text: String?) = text?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    return when {
        parts.size == 1 -> BindSpec(null, parts[0], emptyList())
        parts.size == 2 && parts[1].split(',').all { it in BIND_MODE_WORDS } -> BindSpec(null, parts[0], opts(parts[1]))
        else -> BindSpec(parts[0], parts[1], opts(parts.drop(2).joinToString(":").ifEmpty { null }))
    }
}
