package dev.silas.portainer.app.support

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject

/**
 * The system document picker. The chosen file is copied into the app's sandbox (`asCopy`), its size checked before it
 * is read, read off the main thread, and the copy deleted afterwards; the result is delivered on the main thread.
 */
@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    // The composition's scope runs on the main thread.
    val scope = rememberCoroutineScope()
    // The picker holds its delegate weakly: the composition keeps it alive.
    val delegate = remember { PickerDelegate(scope) { callback(it) } }
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

private class PickerDelegate(
    private val scope: CoroutineScope,
    val onPicked: (PickedFile) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        scope.launch {
            val result = withContext(Dispatchers.Default) { readAndDelete(url) }
            onPicked(result)
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = Unit
}

/** The file's size from its attributes, or null when unknown. */
@OptIn(ExperimentalForeignApi::class)
private fun fileSize(url: NSURL): Long? {
    val path = url.path ?: return null
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
    return when (val size = attributes[NSFileSize]) {
        is NSNumber -> size.longLongValue
        is Number -> size.toLong()
        else -> null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAndDelete(url: NSURL): PickedFile {
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        val size = fileSize(url) ?: return PickedFile.Failed(FILE_UNREADABLE_MESSAGE)
        if (size > MAX_PICKED_FILE_BYTES) return PickedFile.Failed(FILE_TOO_LARGE_MESSAGE)
        val data = NSData.dataWithContentsOfURL(url) ?: return PickedFile.Failed(FILE_UNREADABLE_MESSAGE)
        val length = data.length.toLong()
        // The file may have changed since its size was read.
        if (length > MAX_PICKED_FILE_BYTES) return PickedFile.Failed(FILE_TOO_LARGE_MESSAGE)
        val bytes = if (length == 0L) ByteArray(0) else data.bytes?.readBytes(length.toInt()) ?: return PickedFile.Failed(FILE_UNREADABLE_MESSAGE)
        return decodePickedFile(url.lastPathComponent.orEmpty(), bytes)
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
        // `asCopy`: this is the app's own temporary copy.
        NSFileManager.defaultManager.removeItemAtURL(url, null)
    }
}
