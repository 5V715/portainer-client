package dev.silas.portainer.app.editor.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.editor.EditorSection
import dev.silas.portainer.app.editor.EditorState
import dev.silas.portainer.app.editor.EditorViewModel

private val PROTOCOLS = listOf("tcp" to "TCP", "udp" to "UDP")

@Composable
fun PortsSection(state: EditorState, vm: EditorViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = !state.readOnly
    val ports = state.draft.ports
    val summary = when {
        ports.isEmpty() -> if (state.draft.publishAll) "All exposed ports" else "None published"
        else -> ports.joinToString(", ") { "${it.hostPort.ifEmpty { "random" }}→${it.containerPort}/${it.protocol}" }
    }
    SectionCard("Ports", summary, expanded, onToggle, state.visibleErrors(EditorSection.Ports)) {
        ports.forEachIndexed { i, p ->
            RowHeader("Port ${i + 1}", "Remove port ${i + 1}", enabled) { vm.removePort(i) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormTextField(
                    p.hostPort, { vm.updatePort(i, p.copy(hostPort = it)) }, "Host port", enabled,
                    modifier = Modifier.weight(1f), placeholder = "Random", keyboardType = KeyboardType.Number,
                )
                FormTextField(
                    p.containerPort, { vm.updatePort(i, p.copy(containerPort = it)) }, "Container port", enabled,
                    modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormTextField(
                    p.hostIp, { vm.updatePort(i, p.copy(hostIp = it)) }, "Host IP", enabled,
                    modifier = Modifier.weight(1f), mono = true, placeholder = "All", keyboardType = KeyboardType.Uri,
                )
                ChoiceChips(PROTOCOLS, p.protocol, enabled) { vm.updatePort(i, p.copy(protocol = it)) }
            }
        }
        TextButton(onClick = vm::addPort, enabled = enabled) { Text("Add port") }
        SwitchRow("Publish all exposed ports", state.draft.publishAll, enabled, vm::setPublishAll)
    }
}
