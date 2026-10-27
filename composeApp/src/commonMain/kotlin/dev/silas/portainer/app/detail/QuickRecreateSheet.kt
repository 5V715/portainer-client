package dev.silas.portainer.app.detail

import dev.silas.portainer.app.StackDetail
import dev.silas.portainer.core.recreate.RecreateWarning
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.recreate.RegistryPicker
import dev.silas.portainer.app.recreate.warningText
import dev.silas.portainer.app.theme.StatusTone
import dev.silas.portainer.app.theme.color

/** Bottom sheet for a one-tap recreate with the container's exact settings. [onStarted] navigates to the progress screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickRecreateSheet(viewModel: QuickRecreateViewModel, onStarted: () -> Unit, onOpenStack: (StackDetail) -> Unit) {
    val state = viewModel.state.collectAsState().value
    LaunchedEffect(state.phase) {
        if (state.phase == QuickPhase.Started) {
            viewModel.dismiss()
            onStarted()
        }
    }
    if (state.phase == QuickPhase.Closed || state.phase == QuickPhase.Started) return
    val starting by rememberUpdatedState(state.starting)
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { !starting })
    ModalBottomSheet(onDismissRequest = viewModel::dismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recreate", style = MaterialTheme.typography.titleMedium)
            if (state.phase == QuickPhase.Loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else {
                Text("The container is recreated with exactly the same settings.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pull latest image", Modifier.weight(1f))
                    Switch(checked = state.pull, onCheckedChange = viewModel::setPull, enabled = !state.starting)
                }
                if (state.pull) RegistryPicker(state.registries, state.registryId, viewModel::setRegistry)
                state.warnings.forEach { w ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(warningText(w), color = StatusTone.Warn.color())
                            val link = state.stackLink
                            if (w is RecreateWarning.StackMember && link != null) {
                                TextButton(
                                    onClick = { viewModel.dismiss(); onOpenStack(link) },
                                    enabled = !state.starting,
                                ) { Text("Open stack") }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = viewModel::dismiss, enabled = !state.starting) { Text("Cancel") }
                    Button(onClick = viewModel::confirm, enabled = !state.starting) { Text(if (state.starting) "Starting…" else "Recreate") }
                }
            }
        }
    }
}
