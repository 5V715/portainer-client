package dev.silas.portainer.app.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.support.FeedClaudeBox
import dev.silas.portainer.app.theme.BackButton

const val APP_VERSION = "0.1.0"
const val REPO_URL = "https://github.com/5V715/portainer-client"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("About") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Portainer Client", style = MaterialTheme.typography.headlineSmall)
            Text("Version $APP_VERSION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("A small client for checking on and restarting your Docker containers through Portainer.")
            TextButton(onClick = { uri.openUri(REPO_URL) }) { Text("Source on GitHub") }
            FeedClaudeBox()
        }
    }
}
