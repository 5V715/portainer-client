package dev.silas.portainer.app.editor.sections

import androidx.compose.runtime.Composable
import dev.silas.portainer.app.editor.EditorMode
import dev.silas.portainer.app.editor.EditorSection
import dev.silas.portainer.app.editor.EditorState
import dev.silas.portainer.app.editor.EditorViewModel
import dev.silas.portainer.app.recreate.RegistryPicker
import dev.silas.portainer.core.recreate.isProtectedImage

@Composable
fun ImageSection(state: EditorState, vm: EditorViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = !state.readOnly
    SectionCard("Image", state.draft.image, expanded, onToggle, state.visibleErrors(EditorSection.Image)) {
        FormTextField(state.draft.image, vm::setImage, "Image", enabled, mono = true, placeholder = "nginx:latest")
        if (state.mode == EditorMode.Edit && !state.readOnly && isProtectedImage(state.form.image)) {
            HintText("Replace isn't available for a Portainer image; deploy a copy instead.")
        }
        SwitchRow("Pull image", state.pull, enabled, vm::setPull)
        RegistryPicker(state.registries, state.registryId, vm::setRegistry, enabled)
    }
}
