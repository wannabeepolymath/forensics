package com.forensics.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.forensics.core.app.EditClassification
import com.forensics.core.app.FieldGroup
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

@Composable
fun ForensicsScreen(
    state: UiState,
    onPick: () -> Unit,
    classify: (MetadataField, Value) -> EditClassification,
    onApplyEdit: (MetadataField, Value) -> Unit,
    onFocusBytes: (Long, Int) -> Unit,
    onStringsFilter: (query: String, minLen: Int) -> Unit,
) {
    var editing by remember { mutableStateOf<MetadataField?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    // Spotlight a byte range AND switch to the Hex tab in one gesture.
    val focusInHex: (Long, Int) -> Unit = { offset, length -> onFocusBytes(offset, length); tab = 1 }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Button(onClick = onPick) { Text("Open file") }

        state.identity?.let { id ->
            Text(id.displayName ?: "(unnamed)", style = MaterialTheme.typography.titleMedium)
            Text(
                "${id.sizeBytes ?: 0} bytes · ${id.mimeType ?: "?"} · ${state.handlerName ?: "unknown format"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "sha256: ${state.sha256.take(16)}…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (state.identity != null) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "⚠ Edits modify your original file directly.",
                    Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        }

        if (state.identity != null) {
            val titles = listOf("Metadata", "Hex", "Strings")
            ScrollableTabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> MetadataTab(state.groups, onEdit = { editing = it }, onFocusBytes = focusInHex)
                1 -> HexView(
                    lines = state.hexLines,
                    pageStart = state.hexPageStart,
                    focusOffset = state.focusOffset,
                    focusLength = state.focusLength,
                    fileSize = state.identity?.sizeBytes ?: 0L,
                )
                else -> StringsView(
                    strings = state.strings,
                    truncated = state.stringsTruncated,
                    query = state.stringsQuery,
                    minLen = state.stringsMinLen,
                    onFilterChange = onStringsFilter,
                    onJump = focusInHex,
                )
            }
        }
    }

    editing?.let { field ->
        EditDialog(
            field = field,
            classify = { v -> classify(field, v) },
            onDismiss = { editing = null },
            onConfirm = { v -> editing = null; onApplyEdit(field, v) },
        )
    }
}

@Composable
private fun MetadataTab(
    groups: List<FieldGroup>,
    onEdit: (MetadataField) -> Unit,
    onFocusBytes: (Long, Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth()) {
        groups.forEach { group ->
            item {
                Text(
                    group.name,
                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(group.fields) { field ->
                FieldRow(field, onEdit, onFocusBytes)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FieldRow(
    field: MetadataField,
    onEdit: (MetadataField) -> Unit,
    onFocusBytes: (Long, Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${field.key} = ${valueText(field.value)}")
        Text(
            if (field.editable) "in-place ✓ · tap to edit" else "read-only 🔒",
            style = MaterialTheme.typography.bodySmall,
        )
        // Fields that map to real bytes (offset/length > 0) can be located in the hex view.
        if (field.byteLength > 0) {
            Text(
                "▸ bytes 0x%08x (%d) — show in hex".format(field.byteOffset, field.byteLength),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { onFocusBytes(field.byteOffset, field.byteLength) },
            )
        }
        if (field.editable) {
            Button(onClick = { onEdit(field) }, Modifier.padding(top = 2.dp)) { Text("Edit") }
        }
    }
}

private fun valueText(v: Value): String = when (v) {
    is Value.Text -> v.s
    is Value.Integer -> v.n.toString()
    is Value.Raw -> "<${v.bytes.size} bytes>"
}
