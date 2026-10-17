package dev.silas.portainer.app.registries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.theme.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistryEditScreen(viewModel: RegistryEditViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(viewModel.saved) { if (viewModel.saved) onDone() }
    val vm = viewModel

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isEdit) "Edit registry" else "Add registry") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.loading) {
                Text("Loading...")
                return@Column
            }
            if (vm.isEdit) {
                Text(typeLabel(vm.type), style = MaterialTheme.typography.titleMedium)
            } else {
                TypeSelector(vm)
            }
            OutlinedTextField(
                value = vm.name,
                onValueChange = { vm.name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = vm.errors.name != null,
                supportingText = vm.errors.name?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            if (vm.urlEditable) {
                OutlinedTextField(
                    value = vm.url,
                    onValueChange = { vm.url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    isError = vm.errors.url != null,
                    supportingText = vm.errors.url?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("URL: ${vm.url.ifEmpty { fixedUrl(vm.type) ?: "" }}", style = MaterialTheme.typography.bodyMedium)
            }
            if (vm.showBaseUrl) {
                OutlinedTextField(
                    value = vm.baseUrl,
                    onValueChange = { vm.baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    supportingText = if (vm.isEdit) ({ Text("Leave empty to keep the current value") }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (vm.showRegion) {
                OutlinedTextField(
                    value = vm.region,
                    onValueChange = { vm.region = it },
                    label = { Text("Region") },
                    singleLine = true,
                    isError = vm.errors.region != null,
                    supportingText = vm.errors.region?.let { { Text(it) } }
                        ?: if (vm.isEdit) ({ Text("Leave empty to keep the current value") }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Authentication", Modifier.weight(1f))
                Switch(checked = vm.authentication, onCheckedChange = { vm.authentication = it })
            }
            if (vm.authentication) {
                OutlinedTextField(
                    value = vm.username,
                    onValueChange = { vm.username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    isError = vm.errors.username != null,
                    supportingText = vm.errors.username?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.password,
                    onValueChange = { vm.password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = vm.errors.password != null,
                    supportingText = vm.errors.password?.let { { Text(it) } }
                        ?: if (vm.isEdit) ({ Text("Leave empty to keep the current password") }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::save, enabled = !vm.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.saving) "Saving..." else "Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(vm: RegistryEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = typeLabel(vm.type),
            onValueChange = {},
            readOnly = true,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SELECTABLE_TYPES.forEach { t ->
                DropdownMenuItem(text = { Text(typeLabel(t)) }, onClick = { vm.type = t; expanded = false })
            }
        }
    }
}
