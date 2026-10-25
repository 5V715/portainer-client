package dev.silas.portainer.app.networks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateNetworkSheet(
    state: CreateState,
    drivers: List<String>,
    onCreate: (NetworkForm) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var driver by remember { mutableStateOf("bridge") }
    var subnet by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf("") }
    var ipRange by remember { mutableStateOf("") }
    var internal by remember { mutableStateOf(false) }
    var attachable by remember { mutableStateOf(false) }
    val options = remember { mutableStateListOf<Pair<String, String>>() }
    val labels = remember { mutableStateListOf<Pair<String, String>>() }
    val creating = state.status == CreateStatus.Creating
    val errors = state.errors

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Create network", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                isError = errors.name != null,
                supportingText = errors.name?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            var menu by remember { mutableStateOf(false) }
            // If overlay disappears from the list (it can't mid-sheet), fall back to bridge.
            val selected = if (driver in drivers) driver else "bridge"
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text("Driver: $selected") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    drivers.forEach { d ->
                        DropdownMenuItem(text = { Text(d) }, onClick = {
                            driver = d
                            menu = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = subnet,
                onValueChange = { subnet = it },
                label = { Text("Subnet (optional)") },
                placeholder = { Text("172.20.0.0/16") },
                isError = errors.subnet != null,
                supportingText = errors.subnet?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = gateway,
                onValueChange = { gateway = it },
                label = { Text("Gateway (optional)") },
                placeholder = { Text("172.20.0.1") },
                isError = errors.gateway != null,
                supportingText = errors.gateway?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ipRange,
                onValueChange = { ipRange = it },
                label = { Text("IP range (optional)") },
                placeholder = { Text("172.20.10.0/24") },
                isError = errors.ipRange != null,
                supportingText = errors.ipRange?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Internal (no outside access)", internal) { internal = it }
            SwitchRow("Attachable", attachable) { attachable = it }
            PairRows("Driver options", options)
            PairRows("Labels", labels)
            Button(
                onClick = {
                    onCreate(NetworkForm(name, selected, subnet, gateway, ipRange, internal, attachable, options.toList(), labels.toList()))
                },
                enabled = !creating,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create") }
            when (val s = state.status) {
                CreateStatus.Idle -> Unit
                CreateStatus.Creating -> Text("Creating…")
                is CreateStatus.Done -> Text(s.text)
                is CreateStatus.Failed -> Text(s.text, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PairRows(title: String, rows: SnapshotStateList<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEachIndexed { i, (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = k,
                    onValueChange = { rows[i] = it to v },
                    label = { Text("Key") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = v,
                    onValueChange = { rows[i] = k to it },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { rows.removeAt(i) }) { Text("Remove") }
            }
        }
        TextButton(onClick = { rows.add("" to "") }) { Text("Add row") }
    }
}
