package dev.silas.portainer.app.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.silas.portainer.core.logs.LogLine
import dev.silas.portainer.core.logs.LogStream
import kotlinx.coroutines.launch

@Composable
fun LogsTab(viewModel: LogsViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoScroll by remember { mutableStateOf(true) }
    // Pairs of (stable sequence number, line); recomputed only when the inputs change, not on every recomposition.
    val visible = remember(state.lines, state.firstSeq, state.query, state.matchesOnly) {
        val needle = state.query.takeIf { state.matchesOnly && it.isNotEmpty() }
        state.lines.mapIndexed { i, l -> (state.firstSeq + i) to l }
            .filter { (_, l) -> needle == null || l.text.contains(needle, ignoreCase = true) }
    }

    // Stop collecting while the app is in the background (or this tab is left); resume from the last line.
    LifecycleResumeEffect(viewModel) {
        viewModel.resume()
        onPauseOrDispose { viewModel.pause() }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Follow the tail only while the viewport was at the bottom before new lines arrived. User scrolling by any
    // input (drag, wheel, keyboard) updates the flag; growth of the list alone never turns it off.
    var programmatic by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.isAtBottom() }.collect { (scrolling, atBottom) ->
            if (atBottom) autoScroll = true else if (scrolling && !programmatic) autoScroll = false
        }
    }
    suspend fun scrollToEnd(lastIndex: Int) {
        programmatic = true
        try {
            listState.scrollToItem(lastIndex)
        } finally {
            programmatic = false
        }
    }
    LaunchedEffect(visible.size, state.firstSeq) {
        if (autoScroll && visible.isNotEmpty()) scrollToEnd(visible.lastIndex)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Controls(state, viewModel)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState) {
                        items(visible, key = { it.first }) { (_, line) -> LogRow(line, state.showTimestamps, state.query) }
                    }
                }
                if (visible.isEmpty()) {
                    Text(
                        if (state.following) "Waiting for output…" else "No log output",
                        Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
            }
        }
        if (!autoScroll && visible.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = {
                    autoScroll = true
                    scope.launch { scrollToEnd(visible.lastIndex) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Text("Jump to latest") }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Controls(state: LogsState, vm: LogsViewModel) {
    OutlinedTextField(
        value = state.query,
        onValueChange = vm::setQuery,
        label = { Text("Search logs") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(state.matchesOnly, { vm.setMatchesOnly(!state.matchesOnly) }, { Text("Matches only") })
        FilterChip(state.showTimestamps, { vm.toggleTimestamps() }, { Text("Timestamps") })
        TAIL_CHOICES.forEach { tail ->
            FilterChip(state.tail == tail, { vm.setTail(tail) }, { Text(tail?.toString() ?: "All") })
        }
    }
}

@Composable
private fun LogRow(line: LogLine, showTimestamp: Boolean, query: String) {
    val color = if (line.stream == LogStream.Stderr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val text = remember(line, showTimestamp, query, highlight) {
        highlighted(if (showTimestamp && line.timestamp != null) "${line.timestamp} ${line.text}" else line.text, query, highlight)
    }
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
}

/** [text] with every case-insensitive occurrence of [query] given a [highlight] background. */
internal fun highlighted(text: String, query: String, highlight: Color): AnnotatedString = buildAnnotatedString {
    if (query.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var from = 0
    while (true) {
        val hit = text.indexOf(query, from, ignoreCase = true)
        if (hit < 0) break
        append(text.substring(from, hit))
        withStyle(SpanStyle(background = highlight)) { append(text.substring(hit, hit + query.length)) }
        from = hit + query.length
    }
    append(text.substring(from))
}

/** True when the last item is the last one in the list and fully inside the viewport (or the list is empty). */
private fun LazyListState.isAtBottom(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 1
}
