package dev.silas.portainer.core.stacks

import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Compose
import it.krzeminski.snakeyaml.engine.kmp.exceptions.Mark
import it.krzeminski.snakeyaml.engine.kmp.exceptions.MarkedYamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.nodes.MappingNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.Node
import it.krzeminski.snakeyaml.engine.kmp.nodes.ScalarNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.SequenceNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.Tag

/**
 * A YAML error. [line] is 1-based. Like the parser, it counts `\r\n`, `\r`, NEL, LS and PS as line
 * breaks, so callers that show line numbers should normalise the text to `\n` first.
 */
data class YamlError(val line: Int, val column: Int?, val message: String)

sealed interface ComposeWarning {
    /** No `services:` mapping (and no `include:` that could supply one). */
    data object NoServices : ComposeWarning

    data class ServiceWithoutImage(val service: String) : ComposeWarning

    /** `build:` in a web-editor stack: Portainer may have no build context for it. */
    data class BuildWithoutContext(val service: String) : ComposeWarning
}

data class ComposeAnalysis(
    val error: YamlError?,
    val warnings: List<ComposeWarning>,
    /** Image references per service, in service order; the same image may appear more than once. */
    val images: List<String>,
    val services: List<String>,
)

private const val MAX_MERGE_DEPTH = 8

/**
 * Parses [text] as YAML and runs the structural checks. Pure and total: it never throws, and it
 * never expands aliases, so alias-heavy files stay cheap. `extends`, `include:`, anchors, merge
 * keys and `${VAR}` interpolation are accepted without complaint.
 */
fun analyzeCompose(text: String, webEditor: Boolean): ComposeAnalysis {
    val root = try {
        Compose(LoadSettings()).compose(text)?.also { findDuplicateKey(it)?.let { dup -> return dup } }
    } catch (e: Throwable) {
        return ComposeAnalysis(error = yamlError(e), warnings = emptyList(), images = emptyList(), services = emptyList())
    }
    return try {
        analyzeStructure(root, webEditor)
    } catch (e: Exception) {
        // Structure checks are best effort; a surprise here must not block the user.
        ComposeAnalysis(error = null, warnings = emptyList(), images = emptyList(), services = emptyList())
    }
}

private const val MULTIPLE_DOCUMENTS = "Multiple YAML documents ('---'). A compose file must contain exactly one."

private fun yamlError(e: Throwable): YamlError {
    val marked = e as? MarkedYamlEngineException
    val multi = marked?.problem?.startsWith("expected a single document") == true
    val mark = marked?.problemMark ?: marked?.contextMark
    val message = if (multi) MULTIPLE_DOCUMENTS else (marked?.problem ?: e.message)?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() } ?: "Invalid YAML"
    return YamlError(line = (mark?.line ?: 0) + 1, column = mark?.let { it.column + 1 }, message = message)
}

/** Docker Compose rejects duplicate mapping keys, but the composer does not; report the first one. */
private fun findDuplicateKey(root: Node): ComposeAnalysis? {
    val seen = HashSet<Node>() // identity: aliases share the anchored node, which is visited once
    val stack = ArrayList<Node>()
    stack += root
    var first: Pair<Mark, String>? = null
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        if (!seen.add(node)) continue
        when (node) {
            is MappingNode -> {
                val keys = HashSet<String>()
                for (t in node.value) {
                    val k = t.keyNode
                    if (k is ScalarNode && k.value != "<<" && !keys.add(k.value)) {
                        val m = k.startMark
                        if (m != null && (first == null || m.index < first.first.index)) first = m to k.value
                    }
                    stack += t.valueNode
                    stack += k
                }
            }
            is SequenceNode -> stack.addAll(node.value)
            else -> Unit
        }
    }
    val (mark, key) = first ?: return null
    val error = YamlError(mark.line + 1, mark.column + 1, "Duplicate key '$key'")
    return ComposeAnalysis(error, emptyList(), emptyList(), emptyList())
}

private fun analyzeStructure(root: Node?, webEditor: Boolean): ComposeAnalysis {
    val top = root as? MappingNode
    val servicesNode = top?.get("services") as? MappingNode
    if (servicesNode == null) {
        val hasInclude = top?.get("include") != null
        val warnings = if (hasInclude) emptyList() else listOf<ComposeWarning>(ComposeWarning.NoServices)
        return ComposeAnalysis(null, warnings, emptyList(), emptyList())
    }
    val warnings = mutableListOf<ComposeWarning>()
    val images = mutableListOf<String>()
    val services = mutableListOf<String>()
    for (tuple in servicesNode.value) {
        val name = (tuple.keyNode as? ScalarNode)?.value ?: continue
        services += name
        val service = tuple.valueNode as? MappingNode
        val image = service?.lookup("image")
        val build = service?.lookup("build")
        val imageText = (image as? ScalarNode)?.takeIf { it.tag != Tag.NULL }?.value?.trim().orEmpty()
        if (imageText.isNotEmpty()) images += imageText
        val hasImage = image != null && (image !is ScalarNode || imageText.isNotEmpty())
        val hasBuild = build != null && (build !is ScalarNode || (build.tag != Tag.NULL && build.value.isNotBlank()))
        val extended = service?.lookup("extends") != null
        if (!hasImage && !hasBuild && !extended) warnings += ComposeWarning.ServiceWithoutImage(name)
        if (webEditor && hasBuild) warnings += ComposeWarning.BuildWithoutContext(name)
    }
    return ComposeAnalysis(null, warnings, images, services)
}

private fun MappingNode.get(key: String): Node? =
    value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode

/** Looks up [key] in this mapping, falling back to YAML merge keys (`<<: *anchor`). */
private fun MappingNode.lookup(key: String, depth: Int = 0): Node? {
    get(key)?.let { return it }
    if (depth >= MAX_MERGE_DEPTH) return null
    for (tuple in value) {
        if ((tuple.keyNode as? ScalarNode)?.value != "<<") continue
        when (val merged = tuple.valueNode) {
            is MappingNode -> merged.lookup(key, depth + 1)?.let { return it }
            is SequenceNode -> for (item in merged.value) {
                (item as? MappingNode)?.lookup(key, depth + 1)?.let { return it }
            }
            else -> Unit
        }
    }
    return null
}
