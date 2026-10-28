package dev.silas.portainer.core.stacks

import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Compose
import it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Parse
import it.krzeminski.snakeyaml.engine.kmp.events.CollectionEndEvent
import it.krzeminski.snakeyaml.engine.kmp.events.CollectionStartEvent
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

/**
 * Deepest collection nesting [analyzeCompose] accepts. The library composes nodes recursively, and on
 * Kotlin/Native and wasm a stack overflow kills the process instead of throwing, so deeper input is
 * rejected before composing. Real compose files nest about ten levels.
 */
internal const val MAX_YAML_DEPTH = 64
internal const val TOO_DEEP_MESSAGE = "The file nests too deeply to check (more than $MAX_YAML_DEPTH levels)."

/**
 * Parses [text] as YAML and runs the structural checks. Pure and total: it never throws, and it
 * never expands aliases, so alias-heavy files stay cheap. `extends`, `include:`, anchors, merge
 * keys and `${VAR}` interpolation are accepted without complaint. Input nested more than
 * [MAX_YAML_DEPTH] levels is reported as an error without being composed.
 */
fun analyzeCompose(text: String, webEditor: Boolean): ComposeAnalysis {
    tooDeepError(text)?.let {
        return ComposeAnalysis(error = it, warnings = emptyList(), images = emptyList(), services = emptyList())
    }
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

/**
 * Walks the event stream (the library's parser keeps its own state stack, so this never recurses)
 * and stops at the first collection nested more than [MAX_YAML_DEPTH] deep. Parse errors are left
 * for the composer to report.
 */
private fun tooDeepError(text: String): YamlError? {
    var depth = 0
    try {
        for (event in Parse(LoadSettings()).parse(text)) {
            when (event) {
                is CollectionStartEvent -> if (++depth > MAX_YAML_DEPTH) {
                    val mark = event.startMark
                    return YamlError(line = (mark?.line ?: 0) + 1, column = mark?.let { it.column + 1 }, message = TOO_DEEP_MESSAGE)
                }
                is CollectionEndEvent -> depth--
                else -> Unit
            }
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

private const val MULTIPLE_DOCUMENTS = "Multiple YAML documents ('---'). A compose file must contain exactly one."

private fun yamlError(e: Throwable): YamlError {
    val marked = e as? MarkedYamlEngineException
    val multi = marked?.problem?.startsWith("expected a single document") == true
    // For the "single document" error the library swaps the roles: contextMark is the extra `---`.
    val mark = if (multi) marked.contextMark ?: marked.problemMark else marked?.problemMark ?: marked?.contextMark
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

/**
 * Looks up [key] in this mapping, falling back to YAML merge keys (`<<: *anchor`) depth first, in
 * order. Each mapping is visited once, so shared or cyclic merges stay linear.
 */
private fun MappingNode.lookup(key: String): Node? {
    val seen = HashSet<Node>() // identity
    val stack = ArrayList<MappingNode>()
    stack += this
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        if (!seen.add(node)) continue
        node.get(key)?.let { return it }
        val merged = ArrayList<MappingNode>()
        for (tuple in node.value) {
            if ((tuple.keyNode as? ScalarNode)?.value != "<<") continue
            when (val v = tuple.valueNode) {
                is MappingNode -> merged += v
                is SequenceNode -> v.value.forEach { item -> (item as? MappingNode)?.let { merged += it } }
                else -> Unit
            }
        }
        for (i in merged.indices.reversed()) stack += merged[i]
    }
    return null
}
