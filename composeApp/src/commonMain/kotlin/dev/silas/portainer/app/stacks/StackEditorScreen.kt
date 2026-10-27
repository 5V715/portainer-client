package dev.silas.portainer.app.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.support.codeeditor.CodeEditor

private enum class EditorPart { File, Environment }

private const val SHOWN_WARNINGS = 2

/**
 * The Editor tab: the compose file in the code editor or the env vars, with the validation result above them.
 * [modifier] must give a bounded height (the screen's content area): the code editor takes the space left and
 * scrolls itself, so the keyboard shrinks it rather than pushing it off screen.
 */
@Composable
fun StackEditorContent(state: StackEditorState, viewModel: StackEditorViewModel, fileError: String?, modifier: Modifier = Modifier) {
    var part by rememberSaveable { mutableStateOf(EditorPart.File) }
    if (!state.ready) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (fileError != null) Text(fileError, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
        }
        return
    }
    val locked = state.updating || state.checking
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            EditorPart.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = part == p,
                    onClick = { part = p },
                    shape = SegmentedButtonDefaults.itemShape(i, EditorPart.entries.size),
                ) {
                    Text(if (p == EditorPart.File) "Compose file" else "Environment (${state.effectiveEnv.size})")
                }
            }
        }
        ValidationSummary(state)
        when (part) {
            EditorPart.File -> CodeEditor(
                value = state.text,
                onValueChange = viewModel::setText,
                errorLine = state.errorLine,
                readOnly = locked,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp),
            )
            EditorPart.Environment -> EnvVarsEditor(
                mode = state.envMode,
                rows = state.env,
                block = state.envBlock,
                errors = state.envErrors,
                enabled = !locked,
                onMode = viewModel::setEnvMode,
                onRow = viewModel::setEnvRow,
                onAdd = viewModel::addEnvRow,
                onRemove = viewModel::removeEnvRow,
                onBlock = viewModel::setEnvBlock,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/** The YAML error (it blocks the update), or the structural warnings (they don't), one or two lines each. */
@Composable
private fun ValidationSummary(state: StackEditorState) {
    val error = state.yamlError
    val warnings = state.warnings
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            error != null -> Text(
                "Line ${error.line}: ${error.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            warnings.isNotEmpty() -> {
                val shown = if (expanded) warnings else warnings.take(SHOWN_WARNINGS)
                shown.forEach {
                    Text(composeWarningText(it), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
                if (warnings.size > shown.size) {
                    TextButton(onClick = { expanded = true }) { Text("${warnings.size - shown.size} more warnings") }
                }
            }
        }
        if (state.envErrors.isNotEmpty()) {
            Text(
                "Environment: ${state.envErrors.first()}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Bottom bar of the Editor tab: Update (opens the review), or the update running. */
@Composable
fun StackEditorBar(state: StackEditorState, onUpdate: () -> Unit) {
    if (!state.ready) return
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                state.updating || state.checking -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (state.updating) "Updating the stack…" else "Checking the stack…")
                }
                else -> {
                    Text(
                        when {
                            state.validating -> "Checking the file…"
                            state.hasUnsavedChanges -> "Unsaved changes"
                            else -> "No changes"
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onUpdate, enabled = state.canUpdate, modifier = Modifier.weight(1f)) { Text("Update") }
                }
            }
        }
    }
}

/** The git stack's file, read-only (it changes in the repository). Bounded height from [modifier]. */
@Composable
fun StackFileView(file: String?, fileError: String?, modifier: Modifier = Modifier) {
    val text = remember(file) { file?.let(::normalizeLineEndings) }
    Box(modifier) {
        when {
            text != null -> CodeEditor(
                value = text,
                onValueChange = {},
                errorLine = null,
                readOnly = true,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
            fileError != null -> Text(fileError, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}
