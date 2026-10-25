package dev.silas.portainer.app.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.editor.sections.AdvancedSection
import dev.silas.portainer.app.editor.sections.EnvSection
import dev.silas.portainer.app.editor.sections.GeneralSection
import dev.silas.portainer.app.editor.sections.ImageSection
import dev.silas.portainer.app.editor.sections.LabelsSection
import dev.silas.portainer.app.editor.sections.NetworksSection
import dev.silas.portainer.app.editor.sections.PortsSection
import dev.silas.portainer.app.editor.sections.ResourcesSection
import dev.silas.portainer.app.editor.sections.RestartSection
import dev.silas.portainer.app.editor.sections.VolumesSection
import dev.silas.portainer.app.theme.BackButton

private const val ADVANCED = "Advanced"

/**
 * The Edit / Duplicate / New container form: collapsible sections, actions in a bottom bar within thumb reach.
 * [onContinue] gets the action once the form validates (the finish flows take over from there).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // BackHandler, as in App.kt
@Composable
fun EditorScreen(viewModel: EditorViewModel, onContinue: (FinishAction) -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    // Image and name open by default; sections with errors open when the user tries to continue.
    var expanded by rememberSaveable { mutableStateOf(listOf(EditorSection.Image.name, EditorSection.General.name)) }
    val unsaved = state.phase == EditorPhase.Ready && !state.readOnly && state.dirty.isNotEmpty()
    val back = { if (unsaved) confirmDiscard = true else onBack() }
    BackHandler(enabled = unsaved) { confirmDiscard = true }

    fun toggle(key: String) {
        expanded = if (key in expanded) expanded - key else expanded + key
    }

    fun finish(action: FinishAction) {
        if (viewModel.review(action)) {
            onContinue(action)
        } else {
            expanded = (expanded + viewModel.state.value.errors.keys.map { it.name }).distinct()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.mode == EditorMode.New) "New container" else "Edit container") },
                navigationIcon = { BackButton(back) },
            )
        },
        bottomBar = { if (state.phase == EditorPhase.Ready) ActionBar(state, viewModel, ::finish) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val phase = state.phase) {
                EditorPhase.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is EditorPhase.Error -> Text(phase.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                EditorPhase.Ready -> Column(
                    Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.readOnlyMessage?.let { ReadOnlyBanner(it) }
                    fun open(s: EditorSection) = s.name in expanded
                    ImageSection(state, viewModel, open(EditorSection.Image)) { toggle(EditorSection.Image.name) }
                    GeneralSection(state, viewModel, open(EditorSection.General)) { toggle(EditorSection.General.name) }
                    PortsSection(state, viewModel, open(EditorSection.Ports)) { toggle(EditorSection.Ports.name) }
                    VolumesSection(state, viewModel, open(EditorSection.Volumes)) { toggle(EditorSection.Volumes.name) }
                    NetworksSection(state, viewModel, open(EditorSection.Networks)) { toggle(EditorSection.Networks.name) }
                    EnvSection(state, viewModel, open(EditorSection.Env)) { toggle(EditorSection.Env.name) }
                    LabelsSection(state, viewModel, open(EditorSection.Labels)) { toggle(EditorSection.Labels.name) }
                    RestartSection(state, viewModel, open(EditorSection.Restart)) { toggle(EditorSection.Restart.name) }
                    ResourcesSection(state, viewModel, open(EditorSection.Resources)) { toggle(EditorSection.Resources.name) }
                    if (state.mode == EditorMode.Edit) {
                        AdvancedSection(state.advanced, ADVANCED in expanded) { toggle(ADVANCED) }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this form will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun ReadOnlyBanner(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "The form is read-only. You can still deploy a copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** Edit: Deploy as copy + Replace; New: Start after creating + Create. */
@Composable
private fun ActionBar(state: EditorState, vm: EditorViewModel, finish: (FinishAction) -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.mode == EditorMode.New) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start after creating", Modifier.weight(1f))
                    Switch(checked = state.startAfterCreate, onCheckedChange = vm::setStartAfterCreate)
                }
                Button(onClick = { finish(FinishAction.Create) }, enabled = state.canCreate, modifier = Modifier.fillMaxWidth()) {
                    Text("Create")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { finish(FinishAction.Copy) }, enabled = state.canCopy, modifier = Modifier.weight(1f)) {
                        Text("Deploy as copy")
                    }
                    Button(onClick = { finish(FinishAction.Replace) }, enabled = state.canReplace, modifier = Modifier.weight(1f)) {
                        Text("Replace")
                    }
                }
            }
        }
    }
}
