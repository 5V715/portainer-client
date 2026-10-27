package dev.silas.portainer.app.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.silas.portainer.core.model.EnvVar

/** Env vars are edited as name/value rows, or as one `.env` block. */
enum class EnvMode { Rows, Block }

/** Why [name] can't be a variable name, or null. */
fun envNameError(name: String): String? = when {
    name.isEmpty() -> "A variable has no name"
    name.any { it.isWhitespace() || it == '=' } -> "Invalid name '$name': no spaces or '='"
    name.startsWith('#') -> "Invalid name '$name': can't start with '#'"
    else -> null
}

/** Name problems and duplicate names (each reported once, in order). Names are case-sensitive, as in Docker. */
fun envErrors(env: List<EnvVar>): List<String> {
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    val duplicates = LinkedHashSet<String>()
    for (v in env) {
        envNameError(v.name)?.let { if (it !in out) out += it }
        if (v.name.isNotEmpty() && !seen.add(v.name)) duplicates += v.name
    }
    duplicates.forEach { out += "Duplicate name: $it" }
    return out
}

private val EXPORT_PREFIX = Regex("""^export\s+(?=\S)""")

/** Index of an inline comment's `#` (whitespace before it) in an unquoted value, or -1. */
private fun inlineCommentStart(s: String): Int {
    for (i in 1 until s.length) if (s[i] == '#' && s[i - 1].isWhitespace()) return i
    return -1
}

/** After a closing quote only whitespace and a `# comment` may follow. */
private fun isTrailingNoise(rest: String): Boolean = rest.isBlank() || rest.trimStart().startsWith('#')

/**
 * Parses a `.env` block into variables, with one message per bad line (`Line N: …`); bad lines are skipped.
 *
 * Syntax (Docker Compose's): blank lines and `#` lines are ignored; `NAME=value`, spaces around the name and an
 * unquoted value trimmed, an unquoted value ends at ` #` (an inline comment), so `a#b` keeps its `#`. `"…"` values
 * take `\\`, `\"`, `\n`, `\r` and `\t` escapes; `'…'` values are literal. A leading `export ` is dropped.
 * CRLF line ends are accepted.
 */
fun parseDotEnv(text: String): Pair<List<EnvVar>, List<String>> {
    val env = ArrayList<EnvVar>()
    val errors = ArrayList<String>()
    text.split('\n').forEachIndexed { index, rawLine ->
        val line = rawLine.removeSuffix("\r")
        val n = index + 1
        val start = line.trimStart()
        if (start.isEmpty() || start.startsWith('#')) return@forEachIndexed
        // `export NAME=value` (shell style) means NAME=value.
        val body = EXPORT_PREFIX.find(start)?.let { start.substring(it.range.last + 1) } ?: start
        val eq = body.indexOf('=')
        if (eq < 0) {
            errors += "Line $n: expected NAME=value"
            return@forEachIndexed
        }
        val name = body.substring(0, eq).trim()
        envNameError(name)?.let {
            errors += "Line $n: $it"
            return@forEachIndexed
        }
        val raw = body.substring(eq + 1)
        val v = raw.trimStart()
        val value: String? = when {
            v.startsWith('"') -> {
                val sb = StringBuilder()
                var i = 1
                var closed = -1
                while (i < v.length) {
                    val c = v[i]
                    if (c == '\\' && i + 1 < v.length) {
                        when (val e = v[i + 1]) {
                            '\\', '"' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> sb.append(c).append(e)
                        }
                        i += 2
                        continue
                    }
                    if (c == '"') {
                        closed = i
                        break
                    }
                    sb.append(c)
                    i++
                }
                when {
                    closed < 0 -> null.also { errors += "Line $n: missing closing quote" }
                    !isTrailingNoise(v.substring(closed + 1)) -> null.also { errors += "Line $n: unexpected text after the closing quote" }
                    else -> sb.toString()
                }
            }
            v.startsWith('\'') -> {
                val closed = v.indexOf('\'', 1)
                when {
                    closed < 0 -> null.also { errors += "Line $n: missing closing quote" }
                    !isTrailingNoise(v.substring(closed + 1)) -> null.also { errors += "Line $n: unexpected text after the closing quote" }
                    else -> v.substring(1, closed)
                }
            }
            else -> {
                val comment = inlineCommentStart(raw)
                (if (comment >= 0) raw.substring(0, comment) else raw).trim()
            }
        }
        if (value != null) env += EnvVar(name, value)
    }
    return env to errors
}

private fun needsQuotes(value: String): Boolean =
    value != value.trim() || value.any { it == '"' || it == '\'' || it == '\\' || it == '\n' || it == '\r' || it == '\t' } ||
        inlineCommentStart(value) >= 0

/** One `NAME=value` line per variable; values that wouldn't read back unchanged are double-quoted and escaped. */
fun formatDotEnv(env: List<EnvVar>): String = buildString {
    for (v in env) {
        append(v.name).append('=')
        if (needsQuotes(v.value)) {
            append('"')
            for (c in v.value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        } else {
            append(v.value)
        }
        append('\n')
    }
}

/** Rows or `.env` block, with the [errors] listed under it. Scrolls itself; give it a bounded height. */
@Composable
fun EnvVarsEditor(
    mode: EnvMode,
    rows: List<EnvVar>,
    block: String,
    errors: List<String>,
    enabled: Boolean,
    onMode: (EnvMode) -> Unit,
    onRow: (Int, EnvVar) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onBlock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            EnvMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, EnvMode.entries.size),
                    enabled = enabled,
                ) { Text(if (m == EnvMode.Rows) "Variables" else ".env") }
            }
        }
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val noAutoCorrect = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
        when (mode) {
            EnvMode.Rows -> {
                if (rows.isEmpty()) Text("No variables.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEachIndexed { i, v ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = v.name,
                            onValueChange = { onRow(i, v.copy(name = it)) },
                            label = { Text("Name") },
                            singleLine = true,
                            enabled = enabled,
                            keyboardOptions = noAutoCorrect,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = v.value,
                            onValueChange = { onRow(i, v.copy(value = it)) },
                            label = { Text("Value") },
                            singleLine = true,
                            enabled = enabled,
                            keyboardOptions = noAutoCorrect,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemove(i) }, enabled = enabled) { Icon(Icons.Default.Delete, "Remove ${v.name}") }
                    }
                }
                TextButton(onClick = onAdd, enabled = enabled) {
                    Icon(Icons.Default.Add, null)
                    Text("Add variable")
                }
            }
            EnvMode.Block -> OutlinedTextField(
                value = block,
                onValueChange = onBlock,
                label = { Text(".env") },
                placeholder = { Text("NAME=value") },
                enabled = enabled,
                keyboardOptions = noAutoCorrect,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
        }
    }
}
