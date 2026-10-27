package dev.silas.portainer.app.stacks

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The pull and redeploy review: the reference (and what it was, when it changes), the compose paths, the env
 * changes, the Re-pull image switch, the (fixed) force redeployment and the connection reminder. CE's manual redeploy
 * always recreates, so force is shown as "always" rather than offered as a switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitRedeploySheet(review: GitRedeployReview, onPullImage: (Boolean) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pull and redeploy?", style = MaterialTheme.typography.headlineSmall)
            ReviewLabel("Reference")
            Text(review.referenceName.ifEmpty { "Default branch" }, fontFamily = FontFamily.Monospace)
            review.previousReferenceName?.let {
                Text("Was ${it.ifEmpty { "the default branch" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ReviewLabel(if (review.composePaths.size == 1) "Compose path" else "Compose paths")
            review.composePaths.forEach { Text(it, fontFamily = FontFamily.Monospace) }
            ReviewLabel("Environment variables")
            if (review.envChanges.isEmpty()) Text("No changes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            review.envChanges.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            ReviewLabel("Redeployment")
            Text(review.forceRedeployment)
            if (review.startsStack) Text("The stack is stopped. Redeploying starts its containers.")
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp)) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Re-pull images and redeploy", Modifier.weight(1f))
                Switch(checked = review.pullImage, onCheckedChange = onPullImage)
            }
            Text(review.reminder, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onConfirm) { Text("Redeploy") }
            }
        }
    }
}

@Composable
internal fun ReviewLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}
