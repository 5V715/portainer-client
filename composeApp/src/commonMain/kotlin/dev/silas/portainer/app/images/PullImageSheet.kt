package dev.silas.portainer.app.images

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private const val NO_REGISTRY = "None (public)"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullImageSheet(state: PullState, viewModel: ImagesViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pull image", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.reference,
                onValueChange = viewModel::setPullReference,
                label = { Text("Image reference") },
                placeholder = { Text("nginx:latest") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            RegistryPicker(state, viewModel)
            val pulling = state.status == PullStatus.Pulling
            Button(
                onClick = viewModel::pull,
                enabled = state.reference.isNotBlank() && !pulling,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pull") }
            when (val s = state.status) {
                PullStatus.Idle -> Unit
                PullStatus.Pulling -> {
                    Text("Pulling…")
                    state.progress?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                is PullStatus.Done -> Text(s.text)
                is PullStatus.Failed -> Text(s.text, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RegistryPicker(state: PullState, viewModel: ImagesViewModel) {
    var open by remember { mutableStateOf(false) }
    val selected = state.registries.firstOrNull { it.id == state.registryId }?.name ?: NO_REGISTRY
    Column {
        Text("Registry", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected, Modifier.weight(1f))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(NO_REGISTRY) }, onClick = { viewModel.setPullRegistry(null); open = false })
                state.registries.forEach { r ->
                    DropdownMenuItem(text = { Text(r.name) }, onClick = { viewModel.setPullRegistry(r.id); open = false })
                }
            }
        }
    }
}
