package dev.silas.portainer.app.support.codeeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Monospace YAML editor: line-number gutter (the [errorLine], 1-based, is marked), syntax colouring,
 * horizontal scrolling inside the editor (lines never wrap), and a toolbar with indent, outdent, undo,
 * redo and find. Tab inserts two spaces (Shift+Tab outdents); Ctrl/Cmd+Z, Ctrl/Cmd+Shift+Z or Ctrl+Y and
 * Ctrl/Cmd+F work with a hardware keyboard. Give it a bounded height; it scrolls vertically itself.
 * Lines are split on `\n`, so callers should normalise line endings first.
 *
 * Controlled: pass back exactly the text [onValueChange] emitted. Any other [value] (initial load, reload,
 * a different stack) is treated as a new document and resets undo history, so undo never crosses documents.
 * Without a bounded height from [modifier] or its parent, it uses a fixed fallback height.
 */
@Composable
fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    errorLine: Int?,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    val history = remember { EditHistory() }
    // Read wherever canUndo/canRedo are read, so the toolbar recomposes when the (plain) history changes.
    var historyVersion by remember { mutableIntStateOf(0) }
    // The last text this editor produced itself; any other `value` is a new document.
    val emitted = remember { TextHolder() }
    // A value set from outside replaces the text; the caret is clamped onto it.
    val shown = if (field.text == value) field else TextFieldValue(value, clampedRange(field.selection, value.length))
    LaunchedEffect(value) {
        if (value != emitted.text) {
            emitted.text = value
            field = shown
            history.reset(shown.toEditorText())
            historyVersion++
        }
    }

    fun apply(next: TextFieldValue) {
        field = next
        emitted.text = next.text
        history.push(next.toEditorText())
        historyVersion++
        if (next.text != value) onValueChange(next.text)
    }
    fun applyOp(op: (EditorText) -> EditorText) = apply(op(shown.toEditorText()).toTextFieldValue())
    val scope = rememberScrollStateScope()
    val editorFocus = remember { FocusRequester() }
    fun restore(t: EditorText?) {
        t ?: return
        field = t.toTextFieldValue()
        emitted.text = t.text
        historyVersion++
        scope.revealAfterLayout(t.selStart)
        if (t.text != value) onValueChange(t.text)
    }

    var findOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val matches = remember(shown.text, query, findOpen) { if (findOpen) findAll(shown.text, query) else emptyList() }
    val selStart = minOf(shown.selection.start, shown.selection.end)
    val selEnd = maxOf(shown.selection.start, shown.selection.end)
    val currentMatch = matches.indexOfFirst { it.first == selStart && it.last + 1 == selEnd }.takeIf { it >= 0 }
    var findAnchor by remember { mutableIntStateOf(0) }

    fun selectMatch(index: Int?) {
        val range = index?.let(matches::getOrNull) ?: return
        field = shown.copy(selection = TextRange(range.first, range.last + 1))
        scope.reveal(range.first)
    }
    fun findNext() = selectMatch(nextMatchIndex(matches, if (currentMatch != null) selStart else selStart - 1))
    fun findPrevious() = selectMatch(previousMatchIndex(matches, selStart))
    LaunchedEffect(query, findOpen) {
        if (findOpen) selectMatch(matches.indexOfFirst { it.first >= findAnchor }.takeIf { it >= 0 } ?: 0)
    }
    fun openFind() {
        findAnchor = selStart
        findOpen = true
    }
    fun closeFind() {
        findOpen = false
        editorFocus.requestFocus()
    }

    fun onKey(e: KeyEvent): Boolean {
        val command = e.isCtrlPressed || e.isMetaPressed
        if (e.key == Key.Tab && !readOnly) {
            if (e.type == KeyEventType.KeyDown) applyOp(if (e.isShiftPressed) ::outdent else ::insertTab)
            return true // also swallow KeyUp so Tab never moves focus out of the editor
        }
        if (e.type != KeyEventType.KeyDown || !command) return false
        return when {
            e.key == Key.Z && !readOnly -> {
                restore(if (e.isShiftPressed) history.redo() else history.undo())
                true
            }
            e.key == Key.Y && !readOnly -> {
                restore(history.redo())
                true
            }
            e.key == Key.F -> {
                openFind()
                true
            }
            else -> false
        }
    }

    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) {
        YamlPalette(
            key = SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold),
            scalar = SpanStyle(color = colors.onSurface),
            string = SpanStyle(color = colors.tertiary),
            comment = SpanStyle(color = colors.onSurfaceVariant, fontStyle = FontStyle.Italic),
            anchor = SpanStyle(color = colors.secondary, fontWeight = FontWeight.SemiBold),
            interpolation = SpanStyle(color = colors.onPrimaryContainer, background = colors.primaryContainer),
            punct = SpanStyle(color = colors.onSurfaceVariant),
            tag = SpanStyle(color = colors.secondary, fontStyle = FontStyle.Italic),
            errorLine = SpanStyle(background = colors.errorContainer.copy(alpha = 0.55f)),
            match = SpanStyle(background = colors.tertiary.copy(alpha = 0.22f)),
            currentMatch = SpanStyle(background = colors.tertiary.copy(alpha = 0.5f)),
        )
    }
    val tokens = remember(shown.text) { tokenizeYaml(shown.text) }
    val lines = remember(shown.text) { lineCount(shown.text) }
    val validErrorLine = errorLine?.takeIf { it in 1..lines }
    val highlighter = remember(shown.text, tokens, palette, validErrorLine, matches, currentMatch) {
        YamlHighlighter(shown.text, tokens, palette, validErrorLine, matches, currentMatch)
    }
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = colors.onSurface,
    )

    BoxWithConstraints(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (constraints.hasBoundedHeight) Modifier.fillMaxHeight() else Modifier.height(FALLBACK_HEIGHT))
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerLowest, RoundedCornerShape(8.dp)),
        ) {
            EditorToolbar(
                readOnly = readOnly,
                canUndo = historyVersion >= 0 && history.canUndo,
                canRedo = historyVersion >= 0 && history.canRedo,
                onIndent = { applyOp(::indent) },
                onOutdent = { applyOp(::outdent) },
                onUndo = { restore(history.undo()) },
                onRedo = { restore(history.redo()) },
                onFind = { if (findOpen) closeFind() else openFind() },
            )
            if (findOpen) {
                FindBar(
                    query = query,
                    onQueryChange = { query = it },
                    matchCount = matches.size,
                    currentMatch = currentMatch,
                    onNext = ::findNext,
                    onPrevious = ::findPrevious,
                    onClose = ::closeFind,
                )
            }
            HorizontalDivider(color = colors.outlineVariant)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().semantics { paneTitle = "YAML editor" }) {
                val viewportHeight = if (maxHeight.isFinite) maxHeight else 0.dp
                Row(Modifier.verticalScroll(scope.vertical).heightIn(min = viewportHeight)) {
                    Gutter(lines, validErrorLine, codeStyle, Modifier.heightIn(min = viewportHeight))
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val viewportWidth = maxWidth
                        Row(Modifier.horizontalScroll(scope.horizontal)) {
                            BasicTextField(
                                value = shown,
                                onValueChange = { if (!readOnly || it.text == shown.text) apply(it) },
                                readOnly = readOnly,
                                textStyle = codeStyle,
                                cursorBrush = SolidColor(colors.primary),
                                visualTransformation = highlighter,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                ),
                                onTextLayout = scope::onLayout,
                                modifier = Modifier
                                    .widthIn(min = viewportWidth)
                                    .heightIn(min = viewportHeight)
                                    .padding(horizontal = EDITOR_HORIZONTAL_PADDING, vertical = EDITOR_VERTICAL_PADDING)
                                    .focusRequester(editorFocus)
                                    .onPreviewKeyEvent(::onKey)
                                    .semantics { if (validErrorLine != null) stateDescription = "Error on line $validErrorLine" },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val EDITOR_VERTICAL_PADDING = 8.dp
private val EDITOR_HORIZONTAL_PADDING = 8.dp
private val REVEAL_MARGIN = 24.dp
private val FALLBACK_HEIGHT = 400.dp
private val GUTTER_MIN_WIDTH = 36.dp

private class TextHolder(var text: String? = null)

private fun clampedRange(r: TextRange, length: Int) = TextRange(r.start.coerceIn(0, length), r.end.coerceIn(0, length))

private fun TextFieldValue.toEditorText(): EditorText {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    return EditorText(text, start until end)
}

private fun EditorText.toTextFieldValue() = TextFieldValue(text, TextRange(selStart, selEnd))

/** Vertical and horizontal scroll state plus the last layout, so find and undo can scroll a spot into view. */
private class EditorScroll(
    val vertical: androidx.compose.foundation.ScrollState,
    val horizontal: androidx.compose.foundation.ScrollState,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val topPaddingPx: Float,
    private val startPaddingPx: Float,
    private val marginPx: Int,
) {
    private var layout: TextLayoutResult? = null
    private var pendingReveal: Int? = null

    fun onLayout(result: TextLayoutResult) {
        layout = result
        pendingReveal?.let {
            pendingReveal = null
            reveal(it)
        }
    }

    /** Reveals [offset] once the next layout (for text that is about to change) is ready. */
    fun revealAfterLayout(offset: Int) {
        pendingReveal = offset
    }

    fun reveal(offset: Int) {
        val layout = layout ?: return
        if (offset > layout.layoutInput.text.length) return
        val box = layout.getCursorRect(offset)
        scope.launch {
            val top = (box.top + topPaddingPx).toInt()
            val viewport = vertical.viewportSize
            if (top < vertical.value || top + box.height > vertical.value + viewport) {
                vertical.animateScrollTo((top - viewport / 3).coerceAtLeast(0))
            }
            val left = (box.left + startPaddingPx).toInt()
            val hViewport = horizontal.viewportSize
            if (left < horizontal.value + marginPx || left > horizontal.value + hViewport - marginPx) {
                horizontal.animateScrollTo((left - hViewport / 3).coerceAtLeast(0))
            }
        }
    }
}

@Composable
private fun rememberScrollStateScope(): EditorScroll {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val coroutines = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(vertical, horizontal, coroutines, density) {
        with(density) {
            EditorScroll(
                vertical,
                horizontal,
                coroutines,
                topPaddingPx = EDITOR_VERTICAL_PADDING.toPx(),
                startPaddingPx = EDITOR_HORIZONTAL_PADDING.toPx(),
                marginPx = REVEAL_MARGIN.roundToPx(),
            )
        }
    }
}

@Immutable
private data class YamlPalette(
    val key: SpanStyle,
    val scalar: SpanStyle,
    val string: SpanStyle,
    val comment: SpanStyle,
    val anchor: SpanStyle,
    val interpolation: SpanStyle,
    val punct: SpanStyle,
    val tag: SpanStyle,
    val errorLine: SpanStyle,
    val match: SpanStyle,
    val currentMatch: SpanStyle,
) {
    fun of(kind: TokenKind): SpanStyle = when (kind) {
        TokenKind.Key -> key
        TokenKind.Scalar -> scalar
        TokenKind.StringLit -> string
        TokenKind.Comment -> comment
        TokenKind.Anchor, TokenKind.Alias -> anchor
        TokenKind.Interpolation -> interpolation
        TokenKind.Punct -> punct
        TokenKind.Plain -> tag
    }
}

/**
 * Colours the text without changing it (identity offset mapping). It is built for one [source] text with
 * its precomputed [tokens], and keeps the styled result, so selection-only changes don't rebuild it.
 */
private class YamlHighlighter(
    private val source: String,
    private val tokens: List<Token>,
    private val palette: YamlPalette,
    private val errorLine: Int?,
    private val matches: List<IntRange>,
    private val currentMatch: Int?,
) : VisualTransformation {
    private var cached: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val styled = cached?.takeIf { it.text == text.text } ?: build(text.text).also { cached = it }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    private fun build(text: String): AnnotatedString {
        val spans = if (text == source) tokens else tokenizeYaml(text)
        val builder = AnnotatedString.Builder(text)
        if (errorLine != null) {
            var start = 0
            repeat(errorLine - 1) { start = text.indexOf('\n', start).let { i -> if (i < 0) text.length else i + 1 } }
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 }
            if (end > start) builder.addStyle(palette.errorLine, start, end)
        }
        spans.forEach { builder.addStyle(palette.of(it.kind), it.start, it.end) }
        if (text == source) {
            matches.forEachIndexed { i, r ->
                builder.addStyle(if (i == currentMatch) palette.currentMatch else palette.match, r.first, r.last + 1)
            }
        }
        return builder.toAnnotatedString()
    }
}

/** Line numbers drawn with the editor's text style, so each number sits on its line. */
@Composable
private fun Gutter(lines: Int, errorLine: Int?, style: TextStyle, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val numbers = remember(lines, errorLine, colors) {
        buildString {
            for (n in 1..lines) {
                if (n > 1) append('\n')
                append(n)
            }
        }.let { all ->
            val builder = AnnotatedString.Builder(all)
            if (errorLine != null) {
                var start = 0
                repeat(errorLine - 1) { start = all.indexOf('\n', start) + 1 }
                val end = all.indexOf('\n', start).let { if (it < 0) all.length else it }
                builder.addStyle(
                    SpanStyle(color = colors.onError, background = colors.error, fontWeight = FontWeight.Bold),
                    start,
                    end,
                )
            }
            builder.toAnnotatedString()
        }
    }
    Text(
        text = numbers,
        style = style.copy(color = colors.onSurfaceVariant, textAlign = TextAlign.End),
        softWrap = false,
        modifier = modifier
            .background(colors.surfaceContainer)
            .widthIn(min = GUTTER_MIN_WIDTH)
            .padding(start = 6.dp, end = 6.dp, top = EDITOR_VERTICAL_PADDING, bottom = EDITOR_VERTICAL_PADDING)
            // Line numbers are noise for screen readers; the field states the error line instead.
            .clearAndSetSemantics {},
    )
}

@Composable
private fun EditorToolbar(
    readOnly: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (!readOnly) {
            TextButton(onClick = onIndent) { Text("Indent") }
            TextButton(onClick = onOutdent) { Text("Outdent") }
            TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
            TextButton(onClick = onRedo, enabled = canRedo) { Text("Redo") }
        }
        IconButton(onClick = onFind) { Icon(Icons.Default.Search, contentDescription = "Find") }
    }
}

@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatch: Int?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Find") },
            suffix = {
                val label = when {
                    query.isEmpty() -> ""
                    matchCount == 0 -> "No matches"
                    currentMatch != null -> "${currentMatch + 1} of $matchCount"
                    else -> "$matchCount matches"
                }
                Text(label, style = MaterialTheme.typography.labelMedium)
            },
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (it.key) {
                        Key.Enter -> {
                            if (it.isShiftPressed) onPrevious() else onNext()
                            true
                        }
                        Key.Escape -> {
                            onClose()
                            true
                        }
                        else -> false
                    }
                },
        )
        IconButton(onClick = onPrevious, enabled = matchCount > 0) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close find") }
    }
}
