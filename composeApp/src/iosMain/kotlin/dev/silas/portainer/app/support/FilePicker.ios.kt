package dev.silas.portainer.app.support

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject

/** The system document picker; the chosen file is copied into the app's sandbox and read from there. */
@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    // The picker holds its delegate weakly: the composition keeps it alive.
    val delegate = remember { PickerDelegate { callback(it) } }
    return remember(delegate) { { present(delegate) } }
}

private fun present(delegate: PickerDelegate) {
    val presenter = topViewController()
    if (presenter == null) {
        delegate.onPicked(PickedFile.Failed(FILE_PICKER_UNSUPPORTED_MESSAGE))
        return
    }
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true)
    picker.delegate = delegate
    picker.allowsMultipleSelection = false
    presenter.presentViewController(picker, animated = true, completion = null)
}

@Suppress("DEPRECATION") // keyWindow: the app has a single window
private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

private class PickerDelegate(val onPicked: (PickedFile) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        onPicked(read(url))
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = Unit
}

@OptIn(ExperimentalForeignApi::class)
private fun read(url: NSURL): PickedFile {
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        val data = NSData.dataWithContentsOfURL(url) ?: return PickedFile.Failed(FILE_UNREADABLE_MESSAGE)
        val length = data.length.toLong()
        if (length > MAX_PICKED_FILE_BYTES) return PickedFile.Failed(FILE_TOO_LARGE_MESSAGE)
        val bytes = if (length == 0L) ByteArray(0) else data.bytes?.readBytes(length.toInt()) ?: return PickedFile.Failed(FILE_UNREADABLE_MESSAGE)
        return decodePickedFile(url.lastPathComponent.orEmpty(), bytes)
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}
