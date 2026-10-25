package dev.silas.portainer.app.editor.sections

import androidx.compose.runtime.Composable
import dev.silas.portainer.app.editor.EditorMode
import dev.silas.portainer.app.editor.EditorSection
import dev.silas.portainer.app.editor.EditorState
import dev.silas.portainer.app.editor.EditorViewModel

private const val ARGS_HINT = "Empty = image default. Quote arguments that contain spaces."

/** Name, command, entrypoint, working directory and user. */
@Composable
fun GeneralSection(state: EditorState, vm: EditorViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = state.editable
    val d = state.draft
    SectionCard("Name & command", d.name.ifEmpty { "Generated name" }, expanded, onToggle, state.visibleErrors(EditorSection.General)) {
        FormTextField(
            d.name, vm::setName, "Name", enabled,
            supporting = if (state.mode == EditorMode.New) "Optional: Docker picks a name when empty" else null,
        )
        FormTextField(d.command, vm::setCommand, "Command", enabled, mono = true, supporting = ARGS_HINT)
        FormTextField(d.entrypoint, vm::setEntrypoint, "Entrypoint", enabled, mono = true, supporting = ARGS_HINT)
        FormTextField(d.workingDir, vm::setWorkingDir, "Working directory", enabled, mono = true, placeholder = "Image default")
        FormTextField(d.user, vm::setUser, "User", enabled, mono = true, placeholder = "Image default")
    }
}
