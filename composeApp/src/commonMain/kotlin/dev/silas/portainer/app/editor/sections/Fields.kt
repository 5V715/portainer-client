package dev.silas.portainer.app.editor.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.theme.SelectableChip

/** A single-line form field; [mono] for paths, commands and other machine text. */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    mono: Boolean = false,
    placeholder: String? = null,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = modifier,
    )
}

/** A text field with suggestions: the user can type any value or pick one of [options]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    enabled: Boolean,
    supporting: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val matching = options.filter { value.isBlank() || it.contains(value.trim(), ignoreCase = true) || it == value }.ifEmpty { options }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            enabled = enabled,
            singleLine = true,
            supportingText = supporting?.let { { Text(it) } },
            trailingIcon = { if (options.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled).fillMaxWidth(),
        )
        if (options.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                matching.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        onValueChange(option)
                        expanded = false
                    })
                }
            }
        }
    }
}

/** Title of a repeated row (e.g. "Port 2") with its remove button. */
@Composable
fun RowHeader(title: String, removeLabel: String, enabled: Boolean, onRemove: () -> Unit) {
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Default.Close, contentDescription = removeLabel) }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** One-of-several choice as chips; wraps onto further lines on narrow screens. */
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            SelectableChip(selected = value == selected, onClick = { if (enabled) onSelect(value) }) { Text(label) }
        }
    }
}

@Composable
fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
