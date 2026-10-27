package dev.silas.portainer.app.support

import androidx.compose.runtime.Composable

/** Larger files are refused: a compose file is far smaller, and the whole text is held and edited in memory. */
const val MAX_PICKED_FILE_BYTES = 1024 * 1024

const val FILE_TOO_LARGE_MESSAGE = "The file is larger than 1 MB."
const val FILE_NOT_TEXT_MESSAGE = "The file isn't a UTF-8 text file."
const val FILE_UNREADABLE_MESSAGE = "The file couldn't be read."
const val FILE_PICKER_UNSUPPORTED_MESSAGE = "Choosing a file is not supported on this device."

/** The result of choosing a file: its text, or why it can't be used. */
sealed interface PickedFile {
    data class Text(val name: String, val text: String) : PickedFile {
        override fun toString(): String = "PickedFile.Text(name=$name, length=${text.length})"
    }

    data class Failed(val message: String) : PickedFile
}

/** [bytes] as text: at most [MAX_PICKED_FILE_BYTES], valid UTF-8 without NUL characters; a leading BOM is dropped. */
fun decodePickedFile(name: String, bytes: ByteArray): PickedFile {
    if (bytes.size > MAX_PICKED_FILE_BYTES) return PickedFile.Failed(FILE_TOO_LARGE_MESSAGE)
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        return PickedFile.Failed(FILE_NOT_TEXT_MESSAGE)
    }
    return checkPickedText(name, text)
}

/** Text a platform already decoded strictly: NUL characters mean a binary file; a leading BOM is dropped. */
fun checkPickedText(name: String, text: String): PickedFile =
    if ('\u0000' in text) PickedFile.Failed(FILE_NOT_TEXT_MESSAGE) else PickedFile.Text(name, text.removePrefix("﻿"))

/**
 * Remembers a launcher for the platform's file chooser (Android: the system document picker; iOS: the document
 * picker; web: a file input). Calling it opens the chooser; [onPicked] gets the chosen file's text (decoded with
 * [decodePickedFile] rules) or why it can't be used. Nothing is called when the user cancels.
 */
@Composable
expect fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit
