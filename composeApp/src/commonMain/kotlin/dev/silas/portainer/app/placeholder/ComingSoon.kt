package dev.silas.portainer.app.placeholder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Stand-in for destinations that later tasks implement. */
@Composable
fun ComingSoonScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Coming soon") }
}
