package dev.silas.portainer.app.volumes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVolumeSheet(
    state: CreateState,
    onCreate: (name: String, driver: String, driverOpts: List<Pair<String, String>>, labels: List<Pair<String, String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var driver by remember { mutableStateOf("local") }
    val options = remember { mutableStateListOf<Pair<String, String>>() }
    val labels = remember { mutableStateListOf<Pair<String, String>>() }
    val creating = state.status == CreateStatus.Creating

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Create volume", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                placeholder = { Text("Leave empty to let Docker choose a name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = driver,
                onValueChange = { driver = it },
                label = { Text("Driver") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PairRows("Driver options", options)
            PairRows("Labels", labels)
            Button(
                onClick = { onCreate(name, driver, options.toList(), labels.toList()) },
                enabled = !creating && state.status !is CreateStatus.Done,
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
private fun PairRows(title: String, rows: androidx.compose.runtime.snapshots.SnapshotStateList<Pair<String, String>>) {
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
