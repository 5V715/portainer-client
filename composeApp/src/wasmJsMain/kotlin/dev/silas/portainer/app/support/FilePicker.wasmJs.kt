package dev.silas.portainer.app.support

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.js.ExperimentalWasmJsInterop

/** A hidden `<input type=file>`; the browser reads the file and decodes it as strict UTF-8. */
@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    return remember {
        {
            openFileInput(
                MAX_PICKED_FILE_BYTES,
                onText = { name, text -> callback(checkPickedText(name, text)) },
                onTooLarge = { callback(PickedFile.Failed(FILE_TOO_LARGE_MESSAGE)) },
                onNotText = { callback(PickedFile.Failed(FILE_NOT_TEXT_MESSAGE)) },
                onUnreadable = { callback(PickedFile.Failed(FILE_UNREADABLE_MESSAGE)) },
            )
        }
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun openFileInput(
    maxBytes: Int,
    onText: (String, String) -> Unit,
    onTooLarge: () -> Unit,
    onNotText: () -> Unit,
    onUnreadable: () -> Unit,
): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.yml,.yaml,.txt,.env,text/*,application/yaml,application/x-yaml';
    input.style.display = 'none';
    input.addEventListener('change', () => {
        const file = input.files && input.files[0];
        input.remove();
        if (!file) return;
        if (file.size > maxBytes) { onTooLarge(); return; }
        file.arrayBuffer().then((buffer) => {
            let text;
            try {
                text = new TextDecoder('utf-8', { fatal: true }).decode(buffer);
            } catch (e) {
                onNotText();
                return;
            }
            onText(file.name, text);
        }, () => onUnreadable());
    });
    input.addEventListener('cancel', () => input.remove());
    document.body.appendChild(input);
    input.click();
}""",
)
