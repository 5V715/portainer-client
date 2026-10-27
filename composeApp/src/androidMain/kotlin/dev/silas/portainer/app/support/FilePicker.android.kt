package dev.silas.portainer.app.support

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The system document picker (Storage Access Framework); the file is read off the main thread. */
@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // cancelled
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment.orEmpty()
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        // One byte past the limit is enough to know the file is too large.
                        val buffer = ByteArray(MAX_PICKED_FILE_BYTES + 1)
                        var read = 0
                        while (read < buffer.size) {
                            val n = input.read(buffer, read, buffer.size - read)
                            if (n < 0) break
                            read += n
                        }
                        buffer.copyOf(read)
                    }
                    if (bytes == null) PickedFile.Failed(FILE_UNREADABLE_MESSAGE) else decodePickedFile(name, bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PickedFile.Failed(FILE_UNREADABLE_MESSAGE)
                }
            }
            callback(result)
        }
    }
    // Compose files have no registered MIME type everywhere (text/yaml, application/x-yaml, octet-stream…): any file,
    // checked when read.
    return { launcher.launch(arrayOf("*/*")) }
}
