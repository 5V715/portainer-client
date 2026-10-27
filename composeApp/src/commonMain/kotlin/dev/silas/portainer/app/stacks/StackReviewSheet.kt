package dev.silas.portainer.app.stacks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.silas.portainer.core.stacks.ComposeWarning
import dev.silas.portainer.core.stacks.DiffLine

/** The warning's text in the editor and the review. */
fun composeWarningText(w: ComposeWarning): String = when (w) {
    ComposeWarning.NoServices -> "The file has no services: mapping."
    is ComposeWarning.ServiceWithoutImage -> "Service ${w.service} has neither image nor build."
    is ComposeWarning.BuildWithoutContext -> "Service ${w.service} uses build: Portainer may have no build context for it."
}

/** One row of the diff, keyed by its index in the flattened list (a no-newline marker shares its line's number). */
private sealed interface ReviewRow {
    data class Collapsed(val count: Int) : ReviewRow
    data class Line(val line: DiffLine) : ReviewRow
}

private fun rows(review: StackReview): List<ReviewRow> = buildList {
    for (hunk in review.hunks) {
        if (hunk.collapsedBefore > 0) add(ReviewRow.Collapsed(hunk.collapsedBefore))
        hunk.lines.forEach { add(ReviewRow.Line(it)) }
        if (hunk.collapsedAfter > 0) add(ReviewRow.Collapsed(hunk.collapsedAfter))
    }
}

/**
 * The update review: the compose file's line diff (unchanged regions collapsed to a count), the env changes, the
 * warnings, the connection reminder, and the Re-pull image / Prune switches. Confirm starts the update.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackReviewSheet(
    review: StackReview,
    onPullImage: (Boolean) -> Unit,
    onPrune: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val diff = rows(review)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 24.dp)) {
            item(key = "title") { Text("Update the stack?", style = MaterialTheme.typography.headlineSmall) }
            item(key = "file-title") { SectionTitle("Compose file") }
            if (diff.isEmpty()) {
                item(key = "file-same") { Text("No changes.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            // Keyed by index: a no-newline marker repeats its line's number. Long lines wrap, so the sheet never
            // scrolls sideways.
            itemsIndexed(diff, key = { i, _ -> "diff-$i" }) { _, row -> DiffRow(row) }
            if (review.lineEndingsChanged) {
                item(key = "crlf") {
                    Text(
                        "The stored file uses Windows (CRLF) line ends; it is saved with Unix (LF) ones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (review.startsStack) {
                item(key = "starts") {
                    Text(
                        "The stack is stopped. Updating deploys it, so its containers start.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item(key = "env-title") { SectionTitle("Environment variables") }
            if (review.envChanges.isEmpty()) {
                item(key = "env-same") { Text("No changes.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(review.envChanges, key = { i, _ -> "env-$i" }) { _, change ->
                Text(change, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (review.warnings.isNotEmpty()) {
                item(key = "warnings") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTitle("Warnings")
                        review.warnings.forEach { Text(composeWarningText(it), color = MaterialTheme.colorScheme.tertiary) }
                    }
                }
            }
        }
        // Pinned under the list, so the switches and Update stay in reach however long the diff is.
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp)) {
            HorizontalDivider()
            SwitchRow("Re-pull images and redeploy", review.pullImage, onPullImage)
            if (review.pruneAvailable) SwitchRow("Prune services no longer in the file", review.prune, onPrune)
            Text(review.reminder, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onConfirm) { Text("Update") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DiffRow(row: ReviewRow) {
    val colors = MaterialTheme.colorScheme
    when (row) {
        is ReviewRow.Collapsed -> Text(
            if (row.count == 1) "1 unchanged line" else "${row.count} unchanged lines",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(horizontal = 12.dp, vertical = 4.dp),
        )
        is ReviewRow.Line -> {
            val (sign, number, text, bg) = when (val l = row.line) {
                is DiffLine.Same -> DiffCells(" ", l.newNo, l.text, Color.Transparent)
                is DiffLine.Added -> DiffCells("+", l.newNo, l.text, ADDED)
                is DiffLine.Removed -> DiffCells("-", l.oldNo, l.text, colors.error.copy(alpha = 0.16f))
            }
            Row(Modifier.fillMaxWidth().background(bg)) {
                Text(
                    number.toString(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(40.dp),
                )
                Text(
                    "$sign $text",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
        }
    }
}

private val ADDED = Color(0xFF2E7D32).copy(alpha = 0.18f)

private data class DiffCells(val sign: String, val number: Int, val text: String, val background: Color)
