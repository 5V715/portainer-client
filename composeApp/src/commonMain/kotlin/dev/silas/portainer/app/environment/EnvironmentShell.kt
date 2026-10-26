package dev.silas.portainer.app.environment

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

enum class EnvTab(val label: String, val icon: ImageVector) {
    Containers("Containers", Icons.AutoMirrored.Filled.List),
    Images("Images", Icons.Default.Info),
    Volumes("Volumes", Icons.Default.Home),
    Networks("Networks", Icons.Default.Share),
    Stacks("Stacks", Icons.Default.Build),
}

/** Bottom tab bar around one of the environment's five list screens. */
@Composable
fun EnvironmentShell(selected: EnvTab, onSelect: (EnvTab) -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                EnvTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { if (tab != selected) onSelect(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        // The inner screen's own Scaffold must not add the insets the bar already consumed.
        androidx.compose.foundation.layout.Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

/** Stand-in for the Volumes and Networks tabs until their screens land. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ComingSoonScreen(title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(title) },
                navigationIcon = { dev.silas.portainer.app.theme.BackButton(onBack) },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            Modifier.padding(padding).fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { Text("Coming in this release") }
    }
}

