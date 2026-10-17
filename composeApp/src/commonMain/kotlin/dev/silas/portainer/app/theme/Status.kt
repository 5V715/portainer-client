package dev.silas.portainer.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.Health

enum class StatusTone { Good, Bad, Warn, Idle }

data class Status(val label: String, val tone: StatusTone)

/** The one-word label shown next to the dot: health wins over the raw state while the container is running. */
fun statusOf(state: ContainerState, health: Health?): Status = when {
    health == Health.Unhealthy -> Status("unhealthy", StatusTone.Bad)
    state == ContainerState.Running && health == Health.Starting -> Status("starting", StatusTone.Warn)
    state == ContainerState.Running && health == Health.Healthy -> Status("healthy", StatusTone.Good)
    state == ContainerState.Running -> Status("running", StatusTone.Good)
    state == ContainerState.Restarting -> Status("restarting", StatusTone.Warn)
    state == ContainerState.Paused -> Status("paused", StatusTone.Warn)
    state == ContainerState.Exited -> Status("exited", StatusTone.Idle)
    state == ContainerState.Created -> Status("created", StatusTone.Idle)
    state == ContainerState.Dead -> Status("dead", StatusTone.Idle)
    state == ContainerState.Removing -> Status("removing", StatusTone.Warn)
    else -> Status("unknown", StatusTone.Idle)
}

@Composable
fun StatusTone.color(): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return when (this) {
        StatusTone.Good -> if (dark) Color(0xFF7BD88F) else Color(0xFF1B6E2F)
        StatusTone.Bad -> scheme.error
        StatusTone.Warn -> scheme.tertiary
        StatusTone.Idle -> scheme.onSurfaceVariant
    }
}

@Composable
fun StatusLabel(status: Status, modifier: Modifier = Modifier) {
    val color = status.tone.color()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(status.label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
