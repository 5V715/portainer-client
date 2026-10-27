package dev.silas.portainer.app.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.theme.StatusLabel
import dev.silas.portainer.app.theme.StatusTone
import dev.silas.portainer.app.theme.color
import dev.silas.portainer.app.theme.statusOf
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.Health
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewTab(overview: ContainerOverview?, loading: Boolean, onRefresh: () -> Unit, onOpenStack: (() -> Unit)? = null) {
    PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        if (overview == null) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                if (!loading) Text("Nothing to show. Pull down to retry.", Modifier.padding(16.dp))
            }
            return@PullToRefreshBox
        }
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                overview.stack?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StackBadge(it)
                        if (onOpenStack != null) TextButton(onClick = onOpenStack) { Text("Open stack") }
                    }
                }
                // Why there is no "Edit & recreate" for this container.
                if (overview.protectedImage) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(PROTECTED_IMAGE_MESSAGE, Modifier.padding(12.dp), color = StatusTone.Warn.color())
                    }
                }
                Field("Image", overview.image)
                Column {
                    Text("State", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusLabel(statusOf(ContainerState.fromDocker(overview.state), healthOf(overview.health)))
                }
                Field("Created", overview.created ?: "Unknown")
                Field("Started", overview.startedAt ?: "Never started")
                Field("Restart policy", overview.restartPolicy)
                ListField("Ports", overview.ports)
                ListField("Mounts", overview.mounts)
                ListField("Networks", overview.networks)
                Collapsible("Environment (${overview.env.size})", overview.env)
                Collapsible("Labels (${overview.labels.size})", overview.labels.map { (k, v) -> "$k=$v" }.sorted())
            }
        }
    }
}

@Composable
private fun StackBadge(stack: String) {
    Text(
        "Part of stack $stack",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ListField(label: String, values: List<String>) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (values.isEmpty()) Text("None", style = MaterialTheme.typography.bodyLarge)
        values.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun Collapsible(title: String, values: List<String>) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column {
        HorizontalDivider()
        Text(
            (if (open) "▾ " else "▸ ") + title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        )
        if (open) {
            if (values.isEmpty()) Text("None")
            values.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun healthOf(value: String?): Health? = Health.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
