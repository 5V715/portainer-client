package dev.silas.portainer.app.editor.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** Read-only: settings that are kept but can't be edited in this version. */
@Composable
fun AdvancedSection(advanced: List<Pair<String, String>>, expanded: Boolean, onToggle: () -> Unit) {
    val summary = advanced.joinToString(", ") { it.first }.ifEmpty { "None set" }
    SectionCard("Advanced", summary, expanded, onToggle) {
        HintText("Kept as they are. Not editable in this version.")
        if (advanced.isEmpty()) Text("No devices, capabilities, log, healthcheck, DNS or host settings.")
        advanced.forEach { (label, value) ->
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}
