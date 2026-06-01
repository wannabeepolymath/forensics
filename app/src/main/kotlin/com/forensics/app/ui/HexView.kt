package com.forensics.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensics.core.generic.FoundString
import com.forensics.core.generic.HexFocus

@Composable
fun MonospaceList(lines: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth()) {
        items(lines) { line ->
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun LabeledMonospace(title: String, lines: List<String>) {
    Column {
        Text(title, Modifier.padding(8.dp))
        MonospaceList(lines)
    }
}

private const val HEADER_ITEMS = 1 // the title row above the hex lines, for scroll-index math

/**
 * Hex dump that spotlights the bytes of a tapped metadata field or string. Lines whose byte range
 * overlaps `[focusOffset, focusOffset+focusLength)` get a tinted background, and the list auto-scrolls
 * so the focused bytes are visible. [pageStart] is the absolute offset of the first rendered line.
 */
@Composable
fun HexView(
    lines: List<String>,
    pageStart: Long,
    focusOffset: Long?,
    focusLength: Int,
    fileSize: Long,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(focusOffset, pageStart, lines.size) {
        if (focusOffset != null && lines.isNotEmpty()) {
            val target = HexFocus.lineIndexFor(focusOffset, pageStart) + HEADER_ITEMS
            listState.animateScrollToItem(target.coerceIn(0, lines.size))
        }
    }

    val pageEnd = pageStart + lines.size.toLong() * HexFocus.BYTES_PER_LINE
    val header = if (focusOffset != null) {
        "Hex · bytes 0x%08x–0x%08x of %d · ▸ highlighting 0x%08x".format(pageStart, pageEnd, fileSize, focusOffset)
    } else {
        "Hex · bytes 0x%08x–0x%08x of %d · tap a field to jump here".format(pageStart, pageEnd, fileSize)
    }

    SelectionContainer {
        LazyColumn(Modifier.fillMaxWidth(), state = listState) {
            item {
                Text(header, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
            items(lines.size) { i ->
                val lineStart = pageStart + i.toLong() * HexFocus.BYTES_PER_LINE
                val lit = focusOffset != null &&
                    HexFocus.lineIntersects(lineStart, focusOffset, focusLength)
                Text(
                    lines[i],
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (lit) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * Extracted ASCII strings, each tagged with the absolute offset where it was found. Tapping a row
 * jumps the hex view to that offset. [truncated] flags that the list was capped.
 */
@Composable
fun StringsView(
    strings: List<FoundString>,
    truncated: Boolean,
    onJump: (offset: Long, length: Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            val label = if (truncated) "Strings · showing first ${strings.size} (more not shown) · tap to locate"
            else "Strings · ${strings.size} found · tap to locate"
            Text(label, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
        items(strings) { s ->
            Text(
                "0x%08x  %s".format(s.offset, s.text),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJump(s.offset, s.text.length) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
