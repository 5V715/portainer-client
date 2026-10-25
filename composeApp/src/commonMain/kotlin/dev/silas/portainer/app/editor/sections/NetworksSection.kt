package dev.silas.portainer.app.editor.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.editor.EditorMode
import dev.silas.portainer.app.editor.EditorSection
import dev.silas.portainer.app.editor.EditorState
import dev.silas.portainer.app.editor.EditorViewModel
import dev.silas.portainer.app.editor.NetworkDraft

/** Networks Docker always has, offered even when the list couldn't be loaded. */
private val PREDEFINED = listOf("bridge", "host", "none")

@Composable
fun NetworksSection(state: EditorState, vm: EditorViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = !state.readOnly
    val d = state.draft
    val readOnlyMode = d.networkModeReadOnly
    val summary = readOnlyMode ?: d.networks.joinToString(", ") { it.name }.ifEmpty { "bridge (default)" }
    SectionCard("Networks", summary, expanded, onToggle, state.visibleErrors(EditorSection.Networks)) {
        if (readOnlyMode != null) {
            Text("Network mode: $readOnlyMode", style = MaterialTheme.typography.bodyLarge)
            HintText("Kept as is; it can't be changed in this version.")
            return@SectionCard
        }
        val options = (state.networks + PREDEFINED).distinct()
        d.networks.forEachIndexed { i, net ->
            val title = if (i == 0) "Primary network" else "Additional network $i"
            RowHeader(title, "Remove ${net.name.ifEmpty { title }}", enabled) { vm.removeNetwork(i) }
            NetworkFields(state, net, options, enabled) { vm.updateNetwork(i, it) }
        }
        if (d.networks.isEmpty()) HintText("No network chosen: Docker's default bridge network is used.")
        if (d.networks.isNotEmpty()) HorizontalDivider()
        TextButton(onClick = vm::addNetwork, enabled = enabled) { Text(if (d.networks.isEmpty()) "Add network" else "Add another network") }
    }
}

@Composable
private fun NetworkFields(state: EditorState, net: NetworkDraft, options: List<String>, enabled: Boolean, update: (NetworkDraft) -> Unit) {
    SuggestField(net.name, { update(net.copy(name = it)) }, "Network", options, enabled)
    // host and none take no endpoint settings.
    if (net.name.trim() == "host" || net.name.trim() == "none") return
    FormTextField(net.aliases, { update(net.copy(aliases = it)) }, "Aliases", enabled, mono = true, supporting = "Comma-separated, optional")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormTextField(
            net.ipv4, { update(net.copy(ipv4 = it)) }, "Fixed IPv4", enabled,
            modifier = Modifier.weight(1f), mono = true, placeholder = "Automatic", keyboardType = KeyboardType.Uri,
        )
        FormTextField(
            net.ipv6, { update(net.copy(ipv6 = it)) }, "Fixed IPv6", enabled,
            modifier = Modifier.weight(1f), mono = true, placeholder = "Automatic", keyboardType = KeyboardType.Uri,
        )
    }
    FormTextField(
        net.mac, { update(net.copy(mac = it)) }, "MAC address", enabled, mono = true, placeholder = "Automatic",
        supporting = if (state.mode == EditorMode.Edit) "A copy only keeps a MAC address you type here" else null,
    )
}
